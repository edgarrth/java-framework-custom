#!/bin/bash
# Crea los recursos de AWS que notifications-processor usa en local (se ejecuta cuando LocalStack está listo).
# En los entornos reales estos recursos los crea Terraform, nunca el servicio.
set -euo pipefail
table() { # nombre y luego "attribute:type:KEYTYPE" por cada atributo de clave
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
# cola de auditoría de los servicios, con su cola de mensajes fallidos tras 5 recepciones
dlq_url=$(awslocal sqs create-queue --queue-name axiz-demo-audit-dlq --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name axiz-demo-audit \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null || true
awslocal sns create-topic --name local-customer-notifications >/dev/null || true
# La aplicación local siembra contactos ficticios con el cifrado de campos de Axiz.
# Los scripts de infraestructura no escriben ninguna dirección en texto plano en DynamoDB.
