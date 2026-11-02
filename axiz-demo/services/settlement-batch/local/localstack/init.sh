#!/bin/bash
# Creates the AWS resources settlement-batch uses locally (runs when LocalStack is ready).
# In real environments these resources are created by Terraform, never by the service.
set -euo pipefail
awslocal s3 mb s3://local-bucket || true
