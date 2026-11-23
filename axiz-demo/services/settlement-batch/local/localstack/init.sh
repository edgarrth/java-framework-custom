#!/bin/bash
# Crea los recursos de AWS que settlement-batch usa en local (se ejecuta cuando LocalStack está listo).
# En los entornos reales estos recursos los crea Terraform, nunca el servicio.
set -euo pipefail
awslocal s3 mb s3://local-bucket || true
