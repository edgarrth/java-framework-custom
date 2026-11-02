#!/bin/bash
# Development-only secret files for the whole demo (git-ignored local/secrets of each service).
# Random values, except the client secret of payment-process, which must match the local Keycloak
# realm (local/keycloak/axiz-realm.json). Real environments read secrets from AWS Secrets Manager.
set -Eeuo pipefail
# -E: functions inherit the ERR trap, so an unexpected failure never ends the script silently
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
for s in "$root"/services/*/; do
  bash "$s/local/dev-secrets.sh"
done
printf 'local-only-payment-process' > "$root/services/payment-process/local/secrets/payment-process-client-secret"
echo "secrets ready"
