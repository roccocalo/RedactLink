#!/bin/bash
set -euo pipefail

# ── config ────────────────────────────────────────────────────────────────────
REGION="us-east-1"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
RAW_BUCKET="redactlink-raw-${ACCOUNT_ID}"
SANITIZED_BUCKET="redactlink-sanitized-${ACCOUNT_ID}"
QUEUE_NAME="redactlink-raw-events"
# ──────────────────────────────────────────────────────────────────────────────

echo ""
echo "==> [1/7] Creating SQS queue..."
QUEUE_URL=$(aws sqs create-queue \
  --queue-name "$QUEUE_NAME" \
  --region "$REGION" \
  --query QueueUrl --output text)
echo "    Queue URL: $QUEUE_URL"

QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)
echo "    Queue ARN: $QUEUE_ARN"

echo ""
echo "==> [2/7] Allowing S3 to publish to the queue..."
# Write policy to a temp file to avoid shell-escaping nightmares
POLICY_FILE=$(mktemp)
cat > "$POLICY_FILE" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "s3.amazonaws.com" },
    "Action": "sqs:SendMessage",
    "Resource": "$QUEUE_ARN",
    "Condition": {
      "ArnLike": { "aws:SourceArn": "arn:aws:s3:::$RAW_BUCKET" }
    }
  }]
}
EOF
ATTR_FILE=$(mktemp)
python3 -c "import json; print(json.dumps({'Policy': open('$POLICY_FILE').read()}))" > "$ATTR_FILE"
aws sqs set-queue-attributes \
  --queue-url "$QUEUE_URL" \
  --attributes "file://$ATTR_FILE"
rm "$POLICY_FILE" "$ATTR_FILE"
echo "    Done."

echo ""
echo "==> [3/7] Creating raw-uploads bucket ($RAW_BUCKET)..."
aws s3api create-bucket --bucket "$RAW_BUCKET" --region "$REGION"
aws s3api put-public-access-block \
  --bucket "$RAW_BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
echo "    Public access blocked."

echo ""
echo "==> [4/7] Adding CORS to raw-uploads bucket..."
CORS_FILE=$(mktemp)
cat > "$CORS_FILE" <<'EOF'
{
  "CORSRules": [{
    "AllowedOrigins": ["http://localhost:5173"],
    "AllowedMethods": ["PUT"],
    "AllowedHeaders": ["Content-Type", "Content-Length"],
    "MaxAgeSeconds": 3000
  }]
}
EOF
aws s3api put-bucket-cors --bucket "$RAW_BUCKET" --cors-configuration "file://$CORS_FILE"
rm "$CORS_FILE"
echo "    Done."

echo ""
echo "==> [5/7] Adding lifecycle rule (delete raw files after 24h)..."
LIFECYCLE_FILE=$(mktemp)
cat > "$LIFECYCLE_FILE" <<'EOF'
{
  "Rules": [{
    "ID": "delete-after-24h",
    "Status": "Enabled",
    "Filter": { "Prefix": "" },
    "Expiration": { "Days": 1 }
  }]
}
EOF
aws s3api put-bucket-lifecycle-configuration \
  --bucket "$RAW_BUCKET" \
  --lifecycle-configuration "file://$LIFECYCLE_FILE"
rm "$LIFECYCLE_FILE"
echo "    Done."

echo ""
echo "==> [6/7] Wiring S3 event notification → SQS..."
NOTIF_FILE=$(mktemp)
cat > "$NOTIF_FILE" <<EOF
{
  "QueueConfigurations": [{
    "QueueArn": "$QUEUE_ARN",
    "Events": ["s3:ObjectCreated:*"]
  }]
}
EOF
aws s3api put-bucket-notification-configuration \
  --bucket "$RAW_BUCKET" \
  --notification-configuration "file://$NOTIF_FILE"
rm "$NOTIF_FILE"
echo "    Done."

echo ""
echo "==> [7/7] Creating sanitized-files bucket ($SANITIZED_BUCKET)..."
aws s3api create-bucket --bucket "$SANITIZED_BUCKET" --region "$REGION"
aws s3api put-public-access-block \
  --bucket "$SANITIZED_BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"
echo "    Public access blocked."

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  All done! Add these exports to your shell (~/.zshrc):"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "export AWS_REGION=$REGION"
echo "export AWS_S3_RAW_BUCKET=$RAW_BUCKET"
echo "export AWS_S3_SANITIZED_BUCKET=$SANITIZED_BUCKET"
echo "export AWS_SQS_QUEUE_URL=$QUEUE_URL"
echo ""
