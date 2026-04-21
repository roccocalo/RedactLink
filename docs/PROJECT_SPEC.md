# Zero-Trust Data Sanitizer & Secure Drop — Project Spec

## Problem Statement

Enterprise platform for uploading sensitive documents (logs, text, CSV, PDF).
Before sharing, an AI/NLP service scans for PII (emails, credit cards, IPs, names)
and physically redacts it. Sanitized files are shared via short-lived signed links.
Solves real-world **Data Leakage** — a costly problem for enterprises.

## Tech Stack

| Layer | Technology |
|---|---|
| Frontend | React 19 SPA → Vercel/Netlify |
| Backend | Spring Boot 4 / Java 21 |
| Cloud Storage | AWS S3 (two buckets: raw-uploads, sanitized-files) |
| Async Messaging | AWS SQS |
| Rate Limit / Cache | Redis via Upstash |
| Text Extraction | Apache Tika (all formats → plain text) |
| PII Detection | Microsoft Presidio analyzer (Docker sidecar, port 5002) |
| PDF Redaction | Apache PDFBox 3 (bounding-box → black rectangles) |
| Text/CSV/Log Redaction | Java String API (index-based replacement) |
| DOCX Redaction | Apache POI (paragraph-run redistribution) // Future implementation|
| Real-time Updates | Server-Sent Events (SSE) |

## Data Flows

### Upload Flow — S3 Presigned PUT URL Pattern

```
React                    Spring Boot              AWS S3
  |                           |                      |
  |-- SHA-256(file) --------> |                      |
  |-- POST /uploads/request-url                      |
  |   {filename, size,        |                      |
  |    type, sha256}          |                      |
  |                           |-- check Redis rate limit
  |                           |-- check Redis sha256 dedup
  |                           |-- request Presigned PUT URL
  |                           |<----- presignedUrl --|
  |<-- {presignedUrl, fileId} |                      |
  |                           |                      |
  |-- PUT bytes ---------------------------------> S3 (raw-uploads-bucket)
  |   (direct, backend never touches file bytes)     |
```

Key invariant: **the backend never proxies file bytes**.

### Sanitization Flow — Event-Driven Async

```
S3 (raw-uploads)  →  SQS  →  Spring Boot SQS Listener
                                    |
                         Phase 1: ExtractionService
                                Apache Tika
                             byte[] → String extractedText
                                    |
                         Phase 2: NerService
                              Microsoft Presidio
                          POST /analyze → [{type, start, end}]
                                    |
                         Phase 3: RedactionService (format dispatcher)
                              ┌─ PDF  → PdfRedactionStrategy
                              │         PDFBox: BoundingBoxFinder extends PDFTextStripper
                              │         TextPosition coords → addRect() black rectangles
                              │         + inject audit metadata (PDF-only)
                              │
                              ├─ TXT/CSV/LOG → TextRedactionStrategy
                              │         Sort entities by startIndex DESC
                              │         Replace each range: text[start:end] → [REDACTED_TYPE]
                              │         Re-encode as UTF-8 bytes, same file extension
                              │
                              └─ DOCX → DocxRedactionStrategy // Future implementation
                                        Apache POI XWPFDocument
                                        Per paragraph: concatenate runs → replace → redistribute
                                        into first run (cross-run split handled correctly)
                                    |
                         Upload to sanitized-files-bucket
                         Delete raw file from S3
                         Redis: SET status:{fileId} = COMPLETED
                         SSE: emit COMPLETED event to React client
```

### Download Flow

```
React  →  POST /api/v1/links/{fileId} {expiryMinutes, maxDownloads}
       ←  Spring Boot generates S3 Presigned GET URL (1hr TTL)
       →  User downloads directly from S3
```

## Supported File Types

| Format | MIME type | Redaction method | MVP |
|---|---|---|---|
| PDF | `application/pdf` | PDFBox bounding-box rectangles | ✅ |
| Plain text / Log | `text/plain` | Index-based string replacement | ✅ |
| CSV | `text/csv` | Index-based string replacement | ✅ |
| DOCX | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | Apache POI (paragraph-run redistribution) | ❌ out of scope |
| XLSX, PPTX, etc. | various | — | ❌ out of scope |

DOCX is rejected at upload validation with HTTP 415. The three-phase pipeline
(Extract → Analyze → Redact) stays identical; only the redactor changes per format.

## Key Architectural Invariants

1. Backend NEVER proxies file bytes on upload — always Presigned PUT to S3
2. Idempotency via SHA-256 dedup in Redis — skip reprocessing identical files
3. Three-phase sanitization: Extract → Analyze → Redact (each phase is independent and swappable)
4. SSE for real-time status updates — not polling, not WebSockets
5. Rate limiting on every upload endpoint via Redis sliding window

## Advanced Features

### Idempotency (SHA-256 Dedup)
If 10 users upload the same file, process it only once. Spring Boot checks
`sha256:{hash}` in Redis before enqueuing SQS. If already processed, return
the existing sanitized file URL immediately.

### SSE Real-Time Updates
React opens `EventSource(/api/v1/updates/{fileId})` before the S3 PUT.
Spring Boot holds the connection via `SseEmitter`. When sanitization completes,
the SQS worker fires an SSE event with `{status: "COMPLETED", downloadUrl: "..."}`.
This is the same protocol ChatGPT uses for streaming tokens.

### Audit Metadata (Chain of Custody)
For **PDF files**, PDFBox injects invisible custom metadata into the document properties:
- `Sanitized-By: ZeroTrust-Engine-v1`
- `Sanitized-At: <ISO timestamp>`
- `Redacted-Count: <n>`

For **text-based files** (TXT/CSV/LOG), audit info is appended as a comment footer:
```
# --- REDACTION REPORT ---
# Sanitized-By: ZeroTrust-Engine-v1
# Sanitized-At: 2026-04-16T10:00:00Z
# Redacted-Count: 4
```

## Cost Breakdown (All Free Tier)

| Service | Free Allowance |
|---|---|
| Vercel (Frontend) | Unlimited for static SPA |
| AWS S3 | 5 GB / 2k PUTs / 20k GETs per month (12 months) |
| AWS SQS | 1M requests/month |
| Redis (Upstash) | 10k commands/day |
| AWS EC2 t2.micro (Backend) | 750 hrs/month (12 months) |
| Microsoft Presidio | Open-source Docker image, free |

## Repository Structure

```
RedactLink/
├── backend/            Spring Boot 4 / Java 21
│   └── src/main/java/com/roccocalo/redactlink/
│       ├── controller/
│       ├── service/
│       ├── worker/
│       ├── model/
│       └── config/
├── frontend/           React 19 / TypeScript / Vite
│   └── src/
│       ├── components/
│       ├── hooks/
│       ├── api/
│       └── types/
├── docs/               Architecture diagrams + this spec
├── docker-compose.yml  Local dev: Redis + Presidio sidecars
└── CLAUDE.md           Claude Code project context
```

## Implementation Phases

| Phase | Deliverable |
|---|---|
| 0 | CLAUDE.md files, docs, docker-compose |
| 1 | Backend: PresignedUrlService + UploadController |
| 2 | Backend: SQS listener + ExtractionService (Tika) |
| 3 | Backend: NerService (Presidio) + RedactionService (PDF + TXT/CSV/LOG) |
| 4 | Backend: SSE (SseService + StatusController) + LinkController |
| 5 | Frontend: UploadZone + useUpload hook |
| 6 | Frontend: ProgressTracker + useSSE hook |
| 7 | Frontend: SharePanel + FilePreview + RedactionReport |
| 8 | Integration test end-to-end + demo recording |
