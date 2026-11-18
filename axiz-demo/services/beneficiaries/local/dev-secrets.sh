#!/bin/bash
# Creates random development-only secret files in local/secrets (git-ignored), the directory the
# local profile reads secrets from. Real environments mount them from AWS Secrets Manager through
# the ExternalSecret of the chart. Usage: local/dev-secrets.sh [extra-secret-name...]
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)/secrets"
mkdir -p "$dir" && chmod 700 "$dir"
names=("cursor-key-v1" "field-key-v1" "$@")
for name in "${names[@]}"; do
  [ -z "$name" ] && continue
  if [ ! -f "$dir/$name" ]; then
    # 32 random bytes, base64: valid for keys (crypto) and fine as a local password or API key
    head -c 32 /dev/urandom | base64 | tr -d '\n' > "$dir/$name"
    chmod 600 "$dir/$name"
    echo "created $name"
  fi
done
