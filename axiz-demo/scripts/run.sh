#!/bin/bash
# Runs one service of the demo on this machine against local/compose.yaml:
#   scripts/run.sh payments-business
# AWS calls go to LocalStack (any credentials work there; these are not real ones).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
svc=${1:?service name}
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
cd "$root/services/$svc"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,platform
