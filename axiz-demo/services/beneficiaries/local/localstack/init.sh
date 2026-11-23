#!/bin/bash
# Crea los recursos de AWS que beneficiaries usa en local (se ejecuta cuando LocalStack está listo).
# En los entornos reales estos recursos los crea Terraform, nunca el servicio.
set -euo pipefail
table() { # nombre y luego "attribute:type:KEYTYPE" por cada atributo de clave
  local name=$1 defs=() keys=(); shift
  for k in "$@"; do IFS=: read -r a t kt <<<"$k"
    defs+=("AttributeName=$a,AttributeType=$t"); keys+=("AttributeName=$a,KeyType=$kt"); done
  awslocal dynamodb create-table --table-name "local-$name" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions "${defs[@]}" --key-schema "${keys[@]}" >/dev/null 2>&1 || true
}
table beneficiaries customerId:S:HASH beneficiaryId:S:RANGE
table axiz-idempotency pk:S:HASH
