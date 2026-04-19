#!/usr/bin/env python3
"""
RedactLink — Phase 3 redaction test.

For each test document:
  1. Call Presidio /analyze to detect PII entities
  2. Apply reverse-order index replacement (mirrors Java TextRedactionStrategy)
  3. Append audit footer
  4. Upload redacted file to the sanitized S3 bucket via AWS CLI
  5. Generate a presigned GET URL (1-hour TTL) via AWS CLI
  6. Download via the presigned URL and verify content
"""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

# ── config ─────────────────────────────────────────────────────────────────────
PRESIDIO_URL      = os.getenv("PRESIDIO_ANALYZER_URL", "http://localhost:5002")
SANITIZED_BUCKET  = os.getenv("AWS_S3_SANITIZED_BUCKET")
MIN_SCORE         = 0.7
PRESIGN_EXPIRES   = 3600   # seconds

SCRIPT_DIR = Path(__file__).parent
TEST_FILES = [
    (SCRIPT_DIR / "italian_pii.txt", "text/plain"),
    (SCRIPT_DIR / "english_pii.csv", "text/csv"),
]

# ── colours ────────────────────────────────────────────────────────────────────
RED   = "\033[91m"
GREEN = "\033[92m"
CYAN  = "\033[96m"
BOLD  = "\033[1m"
RESET = "\033[0m"

def header(msg):  print(f"\n{BOLD}{'━'*60}{RESET}\n{BOLD}  {msg}{RESET}\n{'━'*60}")
def step(n, msg): print(f"\n{CYAN}==> [{n}]{RESET} {msg}")
def ok(msg):      print(f"    {GREEN}✓{RESET} {msg}")
def info(msg):    print(f"    {msg}")


# ── Presidio ───────────────────────────────────────────────────────────────────

def check_presidio():
    try:
        with urllib.request.urlopen(f"{PRESIDIO_URL}/health", timeout=5) as r:
            ok(f"Presidio is up ({r.status})")
    except Exception as e:
        print(f"\n{RED}Presidio is not reachable at {PRESIDIO_URL}{RESET}")
        print("Start it with:  docker compose up")
        sys.exit(1)


def analyze(text: str) -> list[dict]:
    body = json.dumps({"text": text, "language": "en"}).encode()
    req  = urllib.request.Request(
        f"{PRESIDIO_URL}/analyze",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        entities = json.loads(r.read())
    return [e for e in entities if e["score"] >= MIN_SCORE]


# ── redaction (mirrors Java TextRedactionStrategy) ─────────────────────────────

def remove_overlaps(entities: list[dict]) -> list[dict]:
    """When two spans overlap, keep the higher-scoring one."""
    kept = []
    for e in sorted(entities, key=lambda x: x["score"], reverse=True):
        if not any(e["start"] < k["end"] and e["end"] > k["start"] for k in kept):
            kept.append(e)
    return kept


def redact(text: str, entities: list[dict]) -> str:
    entities = remove_overlaps(entities)
    # reverse order so replacements don't shift subsequent indices
    for e in sorted(entities, key=lambda x: x["start"], reverse=True):
        text = (
            text[:e["start"]]
            + f"[REDACTED_{e['entity_type']}]"
            + text[e["end"]:]
        )
    text += (
        "\n# --- REDACTION REPORT ---"
        f"\n# Sanitized-By: ZeroTrust-Engine-v1"
        f"\n# Sanitized-At: {datetime.utcnow().isoformat()}Z"
        f"\n# Redacted-Count: {len(entities)}\n"
    )
    return text


# ── AWS helpers (delegate to CLI — no boto3 required) ─────────────────────────

def s3_upload(local_path: Path, s3_key: str, content_type: str):
    subprocess.run(
        ["aws", "s3", "cp", str(local_path),
         f"s3://{SANITIZED_BUCKET}/{s3_key}",
         "--content-type", content_type],
        check=True, capture_output=True,
    )

def s3_presign(s3_key: str) -> str:
    result = subprocess.run(
        ["aws", "s3", "presign",
         f"s3://{SANITIZED_BUCKET}/{s3_key}",
         "--expires-in", str(PRESIGN_EXPIRES)],
        check=True, capture_output=True, text=True,
    )
    return result.stdout.strip()

def http_get(url: str) -> str:
    with urllib.request.urlopen(url, timeout=15) as r:
        return r.read().decode()


# ── main ───────────────────────────────────────────────────────────────────────

def process_file(file_path: Path, content_type: str):
    header(file_path.name)

    # 1 — read
    step(1, "Reading source file")
    original = file_path.read_text(encoding="utf-8")
    info(f"Size: {len(original)} chars, {len(original.splitlines())} lines")

    # 2 — analyze
    step(2, "Calling Presidio /analyze")
    entities = analyze(original)
    ok(f"Found {len(entities)} entities above score threshold ({MIN_SCORE})")
    for e in sorted(entities, key=lambda x: x["start"]):
        snippet = original[e["start"]:e["end"]]
        print(f"    [{e['score']:.2f}] {e['entity_type']:35s} → \"{snippet}\"")

    # 3 — redact
    step(3, "Applying redaction")
    redacted = redact(original, entities)
    ok(f"Redacted text: {len(redacted)} chars")

    # 4 — write temp file and upload
    step(4, f"Uploading redacted file to s3://{SANITIZED_BUCKET}/")
    tmp_path = SCRIPT_DIR / f"_redacted_{file_path.name}"
    tmp_path.write_text(redacted, encoding="utf-8")
    s3_key = f"test/{file_path.stem}_redacted{file_path.suffix}"
    s3_upload(tmp_path, s3_key, content_type)
    tmp_path.unlink()
    ok(f"Uploaded → s3://{SANITIZED_BUCKET}/{s3_key}")

    # 5 — presign
    step(5, "Generating presigned download URL (1-hour TTL)")
    url = s3_presign(s3_key)
    ok("URL generated")
    info(url[:100] + "...")

    # 6 — download and verify
    step(6, "Downloading via presigned URL and verifying")
    downloaded = http_get(url)
    assert "[REDACTED_" in downloaded,   "No redacted tokens found in downloaded file!"
    assert "REDACTION REPORT" in downloaded, "Audit footer missing!"
    ok("File contains redacted tokens")
    ok("Audit footer present")
    info(f"\n{BOLD}--- Redacted content preview (first 800 chars) ---{RESET}")
    info(downloaded[:800])


def main():
    if not SANITIZED_BUCKET:
        print(f"{RED}AWS_S3_SANITIZED_BUCKET is not set. Run: source ~/.zshrc{RESET}")
        sys.exit(1)

    header("RedactLink — Phase 3 Redaction Test")
    step("pre", "Checking Presidio health")
    check_presidio()

    for file_path, content_type in TEST_FILES:
        process_file(file_path, content_type)

    header("All tests passed")


if __name__ == "__main__":
    main()
