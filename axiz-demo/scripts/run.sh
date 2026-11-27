#!/bin/bash
# Ejecuta un servicio de la demo en esta máquina contra local/compose.yaml:
#   scripts/run.sh payment-orders
# Las llamadas a AWS van a LocalStack (allí cualquier credencial funciona; estas no son reales).
set -Eeuo pipefail
# -E: las funciones heredan el trap de ERR, de modo que un fallo inesperado nunca termina el script en silencio
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
svc=${1:?service name}
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
cd "$root/services/$svc"
exec ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,platform
