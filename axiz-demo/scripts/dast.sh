#!/bin/bash
# DAST autenticado de cada servicio REST de la demo con el escaneo de API de OWASP ZAP, guiado por
# los contratos OpenAPI de contracts/ (reglas pasivas y activas). Falla cuando ZAP reporta un FAIL; sus
# advertencias se conservan en los reportes para su revisión.
#
# Local (scripts/demo.sh up): scripts/dast.sh
# En otros entornos: KC_URL=... DAST_TARGETS="payments-ux=http://host:port ..." scripts/dast.sh
# Reportes: target/dast/zap-<service>.{html,json,log}
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC_URL=${KC_URL:-http://localhost:8180}
ZAP_IMAGE=${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:stable}
MAX_MINUTES=${DAST_MAX_MINUTES:-10}
DAST_TARGETS=${DAST_TARGETS:-"payments-ux=http://localhost:8080 payment-orders=http://localhost:8081 \
beneficiaries=http://localhost:8082 banking-core=http://localhost:8083 \
payment-process=http://localhost:8084 payments-history=http://localhost:8086 \
payment-network-gateway=http://localhost:8087"}
# los servicios internos reciben un token de servicio; los orientados al canal, un token de cliente
declare -A machine=([banking-core]=1 [payment-process]=1 [payment-network-gateway]=1)

fail() { printf '\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }
for tool in curl jq docker; do command -v "$tool" >/dev/null || fail "missing tool: $tool"; done

token() {
  local response
  if [ -n "${machine[$1]:-}" ]; then
    response=$(curl -fsS "$KC_URL/realms/axiz/protocol/openid-connect/token" \
      -d grant_type=client_credentials -d client_id=payment-process \
      -d client_secret=local-only-payment-process)
  else
    response=$(curl -fsS "$KC_URL/realms/axiz/protocol/openid-connect/token" \
      -d grant_type=password -d client_id=payments-web -d username=ana -d password=local-only-ana)
  fi
  jq -er '.access_token' <<< "$response"
}

out="$root/target/dast"
mkdir -p "$out"
chmod 777 "$out" # el contenedor de ZAP se ejecuta con un usuario sin privilegios
failed=()
for target in $DAST_TARGETS; do
  service=${target%%=*}
  url=${target#*=}
  contract="$root/contracts/$service.yaml"
  [ -f "$contract" ] || fail "no contract for $service"
  bearer=$(token "$service") || fail "no token for $service"
  # las reglas replacer añaden el bearer token y una clave de idempotencia a cada petición que envía ZAP; -z se
  # divide como una línea de comandos de shell, por lo que el espacio tras "Bearer" se escapa
  rules="-config replacer.full_list(0).description=auth -config replacer.full_list(0).enabled=true"
  rules+=" -config replacer.full_list(0).matchtype=REQ_HEADER"
  rules+=" -config replacer.full_list(0).matchstr=Authorization"
  rules+=" -config replacer.full_list(0).regex=false"
  rules+=" -config replacer.full_list(0).replacement=Bearer\\ $bearer"
  rules+=" -config replacer.full_list(1).description=idempotency"
  rules+=" -config replacer.full_list(1).enabled=true"
  rules+=" -config replacer.full_list(1).matchtype=REQ_HEADER"
  rules+=" -config replacer.full_list(1).matchstr=Idempotency-Key"
  rules+=" -config replacer.full_list(1).regex=false"
  rules+=" -config replacer.full_list(1).replacement=$(cat /proc/sys/kernel/random/uuid)"
  printf '\n== ZAP API scan of %s (%s)\n' "$service" "$url"
  status=0
  docker run --rm --network host \
    -v "$root/contracts:/zap/wrk/contracts:ro" -v "$out:/zap/wrk/out" "$ZAP_IMAGE" \
    zap-api-scan.py -t "/zap/wrk/contracts/$service.yaml" -f openapi -O "$url" -T "$MAX_MINUTES" -I \
    -J "out/zap-$service.json" -r "out/zap-$service.html" -z "$rules" \
    > "$out/zap-$service.log" 2>&1 || status=$?
  grep -E '^(FAIL-NEW|WARN-NEW|PASS):' "$out/zap-$service.log" | tail -1 || true
  [ "$status" = 0 ] || failed+=("$service")
done
[ ${#failed[@]} = 0 ] || fail "ZAP reported failures for: ${failed[*]} (see target/dast)"
printf '\n\033[32mDAST passed for every service\033[0m (reports in target/dast)\n'
