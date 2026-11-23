#!/bin/bash
# Recursos de AWS de toda la demo en LocalStack (se ejecuta cuando LocalStack está listo). En los entornos
# reales los crea Terraform; los servicios nunca crean infraestructura.
set -euo pipefail
here=$(dirname "$0")
table() { # nombre y luego "attribute:type:KEYTYPE" por cada atributo de clave
  local name=$1 defs=() keys=(); shift
  for k in "$@"; do IFS=: read -r a t kt <<<"$k"
    defs+=("AttributeName=$a,AttributeType=$t"); keys+=("AttributeName=$a,KeyType=$kt"); done
  awslocal dynamodb create-table --table-name "local-$name" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions "${defs[@]}" --key-schema "${keys[@]}" >/dev/null 2>&1 || true
}

# beneficiaries
table beneficiaries customerId:S:HASH beneficiaryId:S:RANGE
table axiz-idempotency pk:S:HASH
# notifications-processor
table notification-contacts customerId:S:HASH
table axiz-inbox pk:S:HASH
table axiz-locks pk:S:HASH

# payment-orders: clave KMS para el cifrado envolvente de campos
awslocal kms create-alias --alias-name alias/local \
  --target-key-id "$(awslocal kms create-key --query KeyMetadata.KeyId --output text)" || true

# cola del registro de auditoría (banking-core publica, notifications-processor archiva), DLQ tras 5 intentos
dlq_url=$(awslocal sqs create-queue --queue-name axiz-demo-audit-dlq --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name axiz-demo-audit \
  --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null || true

# solicitudes de notificación (las consume la plataforma de notificaciones; aquí solo un tópico)
topic_arn=$(awslocal sns create-topic --name local-customer-notifications --query TopicArn --output text)
# solo local: una cola suscrita al tópico guarda una copia de cada solicitud de notificación, para que
# scripts/scenarios.sh pueda verificar lo que envió notifications-processor (entrega raw: el cuerpo es el
# mensaje tal como se publicó)
probe_url=$(awslocal sqs create-queue --queue-name local-customer-notifications-probe --query QueueUrl --output text)
probe_arn=$(awslocal sqs get-queue-attributes --queue-url "$probe_url" --attribute-names QueueArn \
  --query Attributes.QueueArn --output text)
awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs --notification-endpoint "$probe_arn" \
  --attributes RawMessageDelivery=true >/dev/null || true

# bucket del archivo de auditoría y de los archivos de liquidación, con un archivo de liquidación de ejemplo
awslocal s3 mb s3://local-bucket || true
awslocal s3 cp "$here/NET-SETTLE-SAMPLE.txt" s3://local-bucket/in/settlement/NET-SETTLE-SAMPLE.txt || true

# La aplicación local siembra contactos ficticios con el cifrado de campos de Axiz.
# Los scripts de infraestructura no escriben ninguna dirección en texto plano en DynamoDB.
