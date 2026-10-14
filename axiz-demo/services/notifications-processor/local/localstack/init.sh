#!/bin/bash
# Creates the AWS resources notifications-processor uses locally (runs when LocalStack is ready).
# In real environments these resources are created by Terraform, never by the service.
set -euo pipefail
table() { # name, then "attribute:type:KEYTYPE" for each key attribute
  local name=$1 defs=() keys=(); shift
  for k in "$@"; do IFS=: read -r a t kt <<<"$k"
    defs+=("AttributeName=$a,AttributeType=$t"); keys+=("AttributeName=$a,KeyType=$kt"); done
  awslocal dynamodb create-table --table-name "local-$name" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions "${defs[@]}" --key-schema "${keys[@]}" >/dev/null 2>&1 || true
}
awslocal s3 mb s3://local-bucket || true
table notification-contacts customerId:S:HASH
table axiz-inbox pk:S:HASH
table axiz-locks pk:S:HASH
# audit queue of the services, with its dead-letter queue after 5 receives
dlq_url=$(awslocal sqs create-queue --queue-name axiz-demo-audit-dlq --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name axiz-demo-audit \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null || true
awslocal sns create-topic --name local-customer-notifications >/dev/null || true
# demo contact (fictitious data): customer C-1001 of the Keycloak demo realm
awslocal dynamodb put-item --table-name local-notification-contacts --item \
  '{"customerId":{"S":"C-1001"},"channel":{"S":"EMAIL"},"address":{"S":"ana.demo@example.com"},"locale":{"S":"es-PE"},"paymentAlerts":{"BOOL":true}}' || true
