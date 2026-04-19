# RedactLink — Phase 3 Redaction Test

This test exercises the NER + redaction pipeline **directly**, without going through
the full Java backend / SQS flow. It calls Presidio over HTTP, applies the same
redaction logic as `TextRedactionStrategy.java`, uploads the result to S3, and
verifies the download via a presigned URL.

---

## Prerequisites

- Docker Desktop running
- `docker compose up` (Redis + custom Presidio image)
- `source ~/.zshrc` (AWS env vars)
- Python 3.11+ (stdlib only — no pip installs required)

---

## Test documents

### `italian_pii.txt`

A fictional Italian consulting contract. Contains:

| Field | Value | Entity type |
|---|---|---|
| Partita IVA | IT01234567897 | `IT_PARTITA_IVA` |
| Codice Fiscale | RSSMRC85M15F205J | `IT_CODICE_FISCALE` |
| IBAN | IT60X0542811101000000123456 | `IT_IBAN` |
| PEC (x2) | rossi.consulting@pec.it / marco.rossi@pec.it | `IT_PEC` |
| Mobile | +39 333 1234567 | `IT_PHONE_NUMBER` |
| Landline | +39 02 87654321 | `IT_PHONE_NUMBER` |
| Email | marco.rossi@gmail.com | `EMAIL_ADDRESS` (default) |
| Address (x2) | Via Garibaldi 42 / Corso Buenos Aires 45 | `IT_ADDRESS` |
| Targa | AB123CD | `IT_TARGA` |
| Driver's licence | U1A123456B | `IT_DRIVERS_LICENCE` |
| Person name | Marco Rossi | `PERSON` (spaCy NLP) |

> The Codice Fiscale `RSSMRC85M15F205J` is the computed-valid CF for Marco Rossi,
> born 15 August 1985 in Milan (municipality code F205). The check character `J` was
> derived from the official check-digit algorithm — our recognizer validates it and
> would reject a wrong check character (e.g. `RSSMRC85M15F205X`).

### `english_pii.csv`

A fictional customer database CSV. Contains:

| Column | Entity type |
|---|---|
| `full_name` | `PERSON` (spaCy NLP) |
| `email` | `EMAIL_ADDRESS` |
| `phone` | `PHONE_NUMBER` |
| `credit_card` | `CREDIT_CARD` (Luhn-validated) |
| `ip_address` | `IP_ADDRESS` |
| `address` | `LOCATION` (spaCy NLP) |

---

## How the script works (`redact_test.py`)

For each test file the script runs 6 steps:

```
[1] Read source file
      → loads the raw text into memory

[2] Call Presidio /analyze
      → POST {"text": "...", "language": "en"} to http://localhost:5002/analyze
      → filters results to score >= 0.7
      → prints each entity: score, type, matched text

[3] Apply redaction
      → sorts entities by startIndex DESC (reverse order)
      → replaces each span: text[start:end] → [REDACTED_TYPE]
      → reverse order is critical: forward replacement shifts indices for later matches
      → appends audit footer (Sanitized-By, Sanitized-At, Redacted-Count)

[4] Upload to S3 sanitized bucket
      → writes redacted content to a temp file
      → aws s3 cp → s3://redactlink-sanitized-{accountId}/test/{name}_redacted.{ext}
      → deletes temp file

[5] Generate presigned URL
      → aws s3 presign → signed GET URL with 1-hour TTL
      → anyone with this URL can download the file for the next hour

[6] Download and verify
      → HTTP GET to the presigned URL
      → asserts [REDACTED_ tokens are present
      → asserts audit footer is present
      → prints first 800 chars of the redacted file
```

**Zero extra dependencies** — only Python stdlib (`urllib`, `json`, `subprocess`, `pathlib`).
AWS operations delegate to the `aws` CLI which is already configured.

---

## How to run

```bash
# Terminal 1 — make sure infra is up
docker compose up

# Terminal 2
source ~/.zshrc
cd /path/to/RedactLink
python3 test/redact_test.py
```

---

## What a successful run looks like

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  italian_pii.txt
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

==> [1] Reading source file
    ✓ Size: 847 chars, 24 lines

==> [2] Calling Presidio /analyze
    ✓ Found 12 entities above score threshold (0.7)
    [0.85] IT_PEC                               → "rossi.consulting@pec.it"
    [0.85] IT_IBAN                              → "IT60X0542811101000000123456"
    [1.00] IT_CODICE_FISCALE                    → "RSSMRC85M15F205J"
    ...

==> [3] Applying redaction
    ✓ Redacted text: 1102 chars

==> [4] Uploading redacted file to s3://redactlink-sanitized-.../
    ✓ Uploaded → s3://.../test/italian_pii_redacted.txt

==> [5] Generating presigned download URL (1-hour TTL)
    ✓ URL generated
    https://redactlink-sanitized-...s3.amazonaws.com/test/italian_pii_redacted.txt?...

==> [6] Downloading via presigned URL and verifying
    ✓ File contains redacted tokens
    ✓ Audit footer present
```

**Sample redacted output (italian_pii.txt):**
```
CONTRATTO DI CONSULENZA INFORMATICA
Data: 15 aprile 2026

COMMITTENTE
Ragione Sociale: [REDACTED_ORGANIZATION] S.r.l.
Partita IVA: [REDACTED_IT_PARTITA_IVA]
Sede legale: [REDACTED_IT_ADDRESS]
PEC aziendale: [REDACTED_IT_PEC]
Telefono: [REDACTED_IT_PHONE_NUMBER]

CONSULENTE
Nome e Cognome: [REDACTED_PERSON]
Codice Fiscale: [REDACTED_IT_CODICE_FISCALE]
...
# --- REDACTION REPORT ---
# Sanitized-By: ZeroTrust-Engine-v1
# Sanitized-At: 2026-04-19T10:00:00Z
# Redacted-Count: 12
```

---

## What this test does NOT cover

- The full Java backend pipeline (SQS → SanitizationService) — that's `infra/smoke-test.sh`
- PDF redaction (Phase 3 extension) — PDFBox bounding-box strategy not yet implemented
- DOCX redaction (Phase 3 extension) — Apache POI strategy not yet implemented
- SSE real-time updates (Phase 4)
- The LinkController presigned URL endpoint (Phase 4) — this test calls `aws s3 presign`
  directly, bypassing the backend

---

## Inspecting results in S3

```bash
# list redacted test files
aws s3 ls s3://$AWS_S3_SANITIZED_BUCKET/test/

# download a redacted file locally
aws s3 cp s3://$AWS_S3_SANITIZED_BUCKET/test/italian_pii_redacted.txt ./redacted.txt
cat redacted.txt
```
