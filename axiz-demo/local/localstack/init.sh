#!/bin/bash
# AWS resources of the whole demo in LocalStack (runs when LocalStack is ready). In real
# environments Terraform creates them; services never create infrastructure.
set -euo pipefail
here=$(dirname "$0")
table() { # name, then "attribute:type:KEYTYPE" for each key attribute
  local name=$1 defs=() keys=(); shift
  for k in "$@"; do IFS=: read -r a t kt <<<"$k"
    defs+=("AttributeName=$a,AttributeType=$t"); keys+=("AttributeName=$a,KeyType=$kt"); done
  awslocal dynamodb create-table --table-name "local-$name" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions "${defs[@]}" --key-schema "${keys[@]}" >/dev/null 2>&1 || true
}

# beneficiaries-crud
table beneficiaries customerId:S:HASH beneficiaryId:S:RANGE
table axiz-idempotency pk:S:HASH
# notifications-processor
table notification-contacts customerId:S:HASH
table axiz-inbox pk:S:HASH
table axiz-locks pk:S:HASH

# payments-business: KMS key for the envelope encryption of fields
awslocal kms create-alias --alias-name alias/local \
  --target-key-id "$(awslocal kms create-key --query KeyMetadata.KeyId --output text)" || true

# audit trail queue (core-integration publishes, notifications-processor archives), DLQ after 5
dlq_url=$(awslocal sqs create-queue --queue-name axiz-demo-audit-dlq --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name axiz-demo-audit \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null || true

# notification requests (consumed by the notifications platform; here only a topic)
topic_arn=$(awslocal sns create-topic --name local-customer-notifications --query TopicArn --output text)
# local only: a queue subscribed to the topic keeps a copy of every notification request, so
# scripts/scenarios.sh can check what notifications-processor sent (raw delivery: the body is the
# message as published)
probe_url=$(awslocal sqs create-queue --queue-name local-customer-notifications-probe --query QueueUrl --output text)
probe_arn=$(awslocal sqs get-queue-attributes --queue-url "$probe_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)
awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs --notification-endpoint "$probe_arn" \
  --attributes RawMessageDelivery=true >/dev/null || true

# bucket of the audit archive and the settlement files, with a sample settlement file
awslocal s3 mb s3://local-bucket || true
awslocal s3 cp "$here/NET-SETTLE-SAMPLE.txt" s3://local-bucket/in/settlement/NET-SETTLE-SAMPLE.txt || true

# demo contact data (fictitious): customers of the Keycloak demo realm
awslocal dynamodb put-item --table-name local-notification-contacts --item \
  '{"customerId":{"S":"C-1001"},"channel":{"S":"EMAIL"},"address":{"S":"ana.demo@example.com"},"locale":{"S":"es-PE"},"paymentAlerts":{"BOOL":true}}' || true
awslocal dynamodb put-item --table-name local-notification-contacts --item \
  '{"customerId":{"S":"C-1002"},"channel":{"S":"SMS"},"address":{"S":"+51900000002"},"locale":{"S":"en"},"paymentAlerts":{"BOOL":true}}' || true
