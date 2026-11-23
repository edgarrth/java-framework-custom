#!/bin/bash
# Crea los recursos de AWS que payment-orders usa en local (se ejecuta cuando LocalStack está listo).
# En los entornos reales estos recursos los crea Terraform, nunca el servicio.
set -euo pipefail
awslocal kms create-alias --alias-name alias/local \
  --target-key-id "$(awslocal kms create-key --query KeyMetadata.KeyId --output text)" || true
