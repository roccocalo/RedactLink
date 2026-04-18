# RedactLink — AWS Infrastructure Setup

## What was created and why

RedactLink needs two S3 buckets, one SQS queue, and the wiring between them.
This document explains every resource, every script, and how to inspect the infrastructure.

---

## AWS Resources

### Region
All resources live in **`us-east-1`** (N. Virginia). Free tier applies to all of them.

---

### S3 Bucket — `redactlink-raw-1234`

The "intake" bucket. The frontend uploads files here directly via a presigned PUT URL.
The backend never touches the file bytes.

| Setting | Value | Why |
|---|---|---|
| Public access | Blocked | Files contain sensitive PII — never public |
| CORS | `PUT` from `localhost:5173` | Allows the browser to PUT directly to S3 |
| Lifecycle rule | Delete objects after 1 day | Raw files contain unredacted PII — auto-purge after 24h |
| Event notification | `ObjectCreated:*` → SQS | Every upload automatically triggers the sanitization pipeline |

The bucket name includes the AWS account ID (`1234`, this is not the real account ID) as a suffix because S3 bucket
names are **globally unique across all AWS accounts and regions** — two people can't have
a bucket named `redactlink-raw`. Using the account ID as a suffix is a standard convention
to guarantee uniqueness without guessing.

---

### S3 Bucket — `redactlink-sanitized-1234`

The "output" bucket. The sanitization worker uploads redacted files here after processing.
Signed download links (`GET` presigned URLs) are generated from this bucket.

| Setting | Value | Why |
|---|---|---|
| Public access | Blocked | Only accessible via time-limited presigned GET URLs |
| Lifecycle rule | None yet | Sanitized files can be kept longer; add one in production |

---

### SQS Queue — `redactlink-raw-events`

A standard SQS queue that acts as the bridge between S3 and the Spring Boot worker.

When a file lands in the raw bucket, AWS automatically sends a JSON message to this queue.
`SqsListener` polls the queue every ~20 seconds (long polling) and processes each message.

**Why SQS instead of processing synchronously?**
Sanitization (Tika extraction + Presidio NER + PDFBox redaction) can take several seconds.
If done synchronously in the upload request, the browser would hang. SQS decouples the
upload from the processing — the user gets an immediate response, and the worker processes
in the background.

**SQS queue policy:**
S3 is not allowed to write to SQS by default. We added a resource-based policy on the queue
that says: "allow `s3.amazonaws.com` to call `sqs:SendMessage` on this queue, but only if
the source is our specific raw bucket." This is the `aws:SourceArn` condition — it prevents
other S3 buckets from accidentally (or maliciously) writing to our queue.

---

## Scripts

### `infra/setup-aws.sh`

Creates all AWS resources from scratch. Safe to re-run — `create-queue` and `create-bucket`
are idempotent (they return the existing resource if the name already exists).

```
[1/7] Create SQS queue
      → captures Queue URL and Queue ARN for use in later steps

[2/7] Set SQS policy
      → allows S3 to publish ObjectCreated events to the queue
      → uses a Python one-liner to double-encode the policy JSON
        (the AWS CLI --attributes flag expects {"Policy": "<json-as-string>"},
         i.e. the policy JSON must itself be a JSON-encoded string value)

[3/7] Create raw-uploads bucket
      → blocks all public access immediately after creation

[4/7] Add CORS to raw-uploads bucket
      → allows PUT from localhost:5173 (frontend dev server)
      → only Content-Type and Content-Length headers needed for presigned PUT

[5/7] Add lifecycle rule to raw-uploads bucket
      → objects expire after 1 day (S3 deletes them automatically)

[6/7] Wire S3 event notification → SQS
      → every ObjectCreated event (PUT, POST, multipart complete) triggers a message

[7/7] Create sanitized-files bucket
      → blocks all public access
```

At the end it prints the four environment variables you need to run the backend.

---

### `infra/smoke-test.sh`

Exercises the full Phase 1 + Phase 2 pipeline automatically. Requires the backend to be
running (`./mvnw spring-boot:run`) and infra to be up (`docker compose up`).

```
[1/6] Health check
      → GET /actuator/health — Spring Boot reports UP only when Redis is also connected

[2/6] Create test file
      → writes a .txt file containing fake PII:
        email, phone number, credit card, IP address
      → computes SHA-256 (used by the backend for dedup)

[3/6] Request presigned URL
      → POST /api/v1/uploads/request-url {filename, size, contentType, sha256}
      → backend checks rate limit, validates file type and size, checks Redis dedup
      → returns {fileId, uploadUrl} — a 5-minute S3 presigned PUT URL

[4/6] Upload directly to S3
      → PUT file bytes straight to the presigned URL — backend never sees the bytes
      → HTTP 200 from S3 means the file landed successfully

[5/6] Verify object in S3
      → aws s3 ls confirms the object exists under {fileId}/smoke-test.txt

[6/6] Wait and check pipeline
      → sleeps 5s to give SqsListener time to pick up the event
      → checks Redis: GET status:{fileId} — should be PROCESSING
        (set by SanitizationService when it starts working)
      → checks SQS queue depth: should be 0 (message consumed and deleted)
```

**What a successful run looks like:**
- Redis status → `PROCESSING`
- SQS depth → `0`
- Backend logs show:
  ```
  [INFO] Received S3 event: fileId=... objectKey=.../smoke-test.txt
  [INFO] fileId=... extracted 168 chars contentType=text/plain
  ```

---

## Environment Variables

Added to `~/.zshrc` so every new terminal session picks them up automatically.
Run `source ~/.zshrc` in an existing terminal to apply them without reopening it.

```bash
export AWS_REGION=us-east-1
export AWS_S3_RAW_BUCKET=redactlink-raw-1234
export AWS_S3_SANITIZED_BUCKET=redactlink-sanitized-1234
export AWS_SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/1234/redactlink-raw-events
```

AWS credentials (`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`) are stored in
`~/.aws/credentials` by `aws configure` — the AWS SDK picks them up automatically
via the Default Credentials Provider chain, so you don't need to export them manually.

---

## IAM User — `redactlink-dev`

Created manually in the AWS Console before running the scripts.
Has `AmazonS3FullAccess` and `AmazonSQSFullAccess` attached — sufficient for development.

In production you would scope these down to specific resources:
```json
{
  "Effect": "Allow",
  "Action": ["s3:PutObject", "s3:GetObject", "s3:HeadObject", "s3:DeleteObject"],
  "Resource": "arn:aws:s3:::redactlink-raw-1234/*"
}
```

---

## How to inspect the infrastructure

### Option 1 — AWS Console (browser)

S3 buckets:
```
https://us-east-1.console.aws.amazon.com/s3/home?region=us-east-1
```

SQS queue:
```
https://us-east-1.console.aws.amazon.com/sqs/v3/home?region=us-east-1
```

> Note: always make sure the region selector in the top-right corner shows **us-east-1**.
> If you navigate from a bookmark set to eu-north-1 you'll see an empty list.

---

### Option 2 — AWS CLI

```bash
# List all objects in the raw bucket
aws s3 ls s3://redactlink-raw-1234 --recursive

# List objects in the sanitized bucket
aws s3 ls s3://redactlink-sanitized-1234 --recursive

# Download a specific file locally
aws s3 cp s3://redactlink-raw-1234/<fileId>/smoke-test.txt ./downloaded.txt

# Check SQS queue depth
aws sqs get-queue-attributes \
  --queue-url $AWS_SQS_QUEUE_URL \
  --attribute-names ApproximateNumberOfMessages

# Peek at a message without consuming it (useful for debugging)
aws sqs receive-message --queue-url $AWS_SQS_QUEUE_URL
```

---

### Option 3 — Cyberduck (GUI desktop app)

[Cyberduck](https://cyberduck.io) is a free GUI that lets you browse S3 buckets like
a file system — drag and drop files, preview content, delete objects.

**Setup:**
1. Download and open Cyberduck
2. Click **Open Connection**
3. Select **Amazon S3** from the dropdown
4. Enter:
   - Access Key ID: your `AWS_ACCESS_KEY_ID`
   - Secret Access Key: your `AWS_SECRET_ACCESS_KEY`
5. Connect — you'll see all your buckets listed
6. Navigate into `redactlink-raw-1234` to see uploaded files

Cyberduck is useful during development to visually confirm files appear and disappear
(once the lifecycle rule kicks in, or once the worker deletes the raw file after redaction).

---

## Running locally (full stack)

```bash
# Terminal 1 — infra
docker compose up                        # Redis on :6379, Presidio on :5002

# Terminal 2 — backend
source ~/.zshrc
cd backend && ./mvnw spring-boot:run

# Terminal 3 — smoke test (optional)
source ~/.zshrc
bash infra/smoke-test.sh
```
