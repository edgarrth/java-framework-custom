#!/bin/bash
# Creates the AWS resources payment-orders uses locally (runs when LocalStack is ready).
# In real environments these resources are created by Terraform, never by the service.
set -euo pipefail
awslocal kms create-alias --alias-name alias/local \
  --target-key-id "$(awslocal kms create-key --query KeyMetadata.KeyId --output text)" || true
