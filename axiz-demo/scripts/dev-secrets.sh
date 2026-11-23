#!/bin/bash
# Archivos de secretos solo para desarrollo de toda la demo (local/secrets de cada servicio, ignorado por git).
# Valores aleatorios, salvo el client secret de payment-process, que debe coincidir con el realm del Keycloak
# local (local/keycloak/axiz-realm.json). Los entornos reales leen los secretos de AWS Secrets Manager.
set -Eeuo pipefail
# -E: las funciones heredan el trap de ERR, de modo que un fallo inesperado nunca termina el script en silencio
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
for s in "$root"/services/*/; do
  bash "$s/local/dev-secrets.sh"
done
printf 'local-only-payment-process' > "$root/services/payment-process/local/secrets/payment-process-client-secret"
echo "secrets ready"
