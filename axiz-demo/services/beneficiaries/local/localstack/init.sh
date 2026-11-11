#!/bin/bash
# Creates the AWS resources beneficiaries uses locally (runs when LocalStack is ready).
# In real environments these resources are created by Terraform, never by the service.
set -euo pipefail
table() { # name, then "attribute:type:KEYTYPE" for each key attribute
  local name=$1 defs=() keys=(); shift
  for k in "$@"; do IFS=: read -r a t kt <<<"$k"
    defs+=("AttributeName=$a,AttributeType=$t"); keys+=("AttributeName=$a,KeyType=$kt"); done
  awslocal dynamodb create-table --table-name "local-$name" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions "${defs[@]}" --key-schema "${keys[@]}" >/dev/null 2>&1 || true
}
table beneficiaries customerId:S:HASH beneficiaryId:S:RANGE
table axiz-idempotency pk:S:HASH
