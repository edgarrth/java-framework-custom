#!/bin/bash
# Runs one service of the demo on this machine against local/compose.yaml:
#   scripts/run.sh payment-orders
# AWS calls go to LocalStack (any credentials work there; these are not real ones).
set -Eeuo pipefail
# -E: functions inherit the ERR trap, so an unexpected failure never ends the script silently
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
svc=${1:?service name}
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
cd "$root/services/$svc"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,platform
