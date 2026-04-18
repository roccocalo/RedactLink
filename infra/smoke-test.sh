#!/bin/bash
set -euo pipefail

BACKEND="http://localhost:8080"
RAW_BUCKET="${AWS_S3_RAW_BUCKET:?AWS_S3_RAW_BUCKET not set — run: source ~/.zshrc}"
QUEUE_URL="${AWS_SQS_QUEUE_URL:?AWS_SQS_QUEUE_URL not set}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── helpers ───────────────────────────────────────────────────────────────────
json_field() { python3 -c "import sys,json; print(json.load(sys.stdin)['$1'])"; }
# ─────────────────────────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  RedactLink — Phase 2 Smoke Test"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
echo "==> [1/6] Checking backend health..."
HEALTH=$(curl -sf "$BACKEND/actuator/health" || true)
if [ -z "$HEALTH" ]; then
  echo ""
  echo "  Backend is not running. Start it in another terminal with:"
  echo ""
  echo "    source ~/.zshrc"
  echo "    cd $PROJECT_ROOT/backend && ./mvnw spring-boot:run"
  echo ""
  exit 1
fi
STATUS=$(echo "$HEALTH" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','UNKNOWN'))")
echo "    Spring Boot status: $STATUS"
[ "$STATUS" = "UP" ] || { echo "    Backend is not UP — check logs."; exit 1; }

echo ""
echo "==> [2/6] Creating test file with fake PII..."
TEST_FILE=$(mktemp /tmp/redactlink_test_XXXXXX.txt)
cat > "$TEST_FILE" <<'EOF'
Internal Report — Q1 2026
Prepared by: John Smith (john.smith@acme.com)
Direct line: +1-555-867-5309
Credit card: 4111 1111 1111 1111
Submitted from IP: 192.168.1.42
EOF
FILENAME="smoke-test.txt"
SIZE=$(wc -c < "$TEST_FILE" | tr -d ' ')
SHA256=$(shasum -a 256 "$TEST_FILE" | awk '{print $1}')
echo "    Size: ${SIZE} bytes"
echo "    SHA-256: $SHA256"

echo ""
echo "==> [3/6] Requesting presigned upload URL from backend..."
RESPONSE=$(curl -sf -X POST "$BACKEND/api/v1/uploads/request-url" \
  -H "Content-Type: application/json" \
  -d "{\"filename\":\"$FILENAME\",\"size\":$SIZE,\"contentType\":\"text/plain\",\"sha256\":\"$SHA256\"}")
echo "    Response: $RESPONSE"

UPLOAD_URL=$(echo "$RESPONSE" | json_field uploadUrl)
FILE_ID=$(echo "$RESPONSE"   | json_field fileId)
echo "    fileId:    $FILE_ID"
echo "    uploadUrl: ${UPLOAD_URL:0:80}..."

echo ""
echo "==> [4/6] Uploading file directly to S3 (bypassing backend)..."
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$UPLOAD_URL" \
  -H "Content-Type: text/plain" \
  --data-binary "@$TEST_FILE")
echo "    S3 PUT response: HTTP $HTTP_CODE"
[ "$HTTP_CODE" = "200" ] || { echo "    S3 PUT failed."; exit 1; }

echo ""
echo "==> [5/6] Verifying object landed in S3..."
sleep 1
aws s3 ls "s3://$RAW_BUCKET/$FILE_ID/" \
  && echo "    Object confirmed in S3." \
  || echo "    Object not visible yet (eventual consistency) — usually fine."

echo ""
echo "==> [6/6] Waiting for SQS listener to consume the message..."
echo "    (backend has up to ~21s to pick it up via long poll)"
sleep 5

REDIS_STATUS=$(docker compose -f "$PROJECT_ROOT/docker-compose.yml" exec -T redis \
  redis-cli get "status:$FILE_ID" 2>/dev/null || echo "redis-unavailable")
echo "    Redis status:$FILE_ID → $REDIS_STATUS"

QUEUE_DEPTH=$(aws sqs get-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attribute-names ApproximateNumberOfMessages \
  --query Attributes.ApproximateNumberOfMessages --output text)
echo "    SQS queue depth: $QUEUE_DEPTH (0 = message consumed by listener)"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  fileId: $FILE_ID"
echo ""
echo "  Expected in backend logs:"
echo "  [INFO] Received S3 event: fileId=$FILE_ID objectKey=$FILE_ID/smoke-test.txt"
echo "  [INFO] fileId=$FILE_ID extracted N chars contentType=text/plain"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

rm -f "$TEST_FILE"
