#!/bin/bash
# Crea archivos de secretos aleatorios solo para desarrollo en local/secrets (ignorado por git), el directorio
# del que el perfil local lee los secretos. Los entornos reales los montan desde AWS Secrets Manager mediante
# el ExternalSecret del chart. Uso: local/dev-secrets.sh [extra-secret-name...]
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)/secrets"
mkdir -p "$dir" && chmod 700 "$dir"
names=("payment-process-client-secret" "$@")
for name in "${names[@]}"; do
  [ -z "$name" ] && continue
  if [ ! -f "$dir/$name" ]; then
    # 32 bytes aleatorios en base64: válidos para claves (crypto) y aptos como contraseña o API key locales
    head -c 32 /dev/urandom | base64 | tr -d '\n' > "$dir/$name"
    chmod 600 "$dir/$name"
    echo "created $name"
  fi
done
