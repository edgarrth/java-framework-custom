#!/bin/bash
# Todas las pruebas de la demo en ejecución con un solo comando, desde cualquier directorio:
#   axiz-demo/scripts/test-all.sh           verificaciones de endpoints, prueba smoke y escenarios funcionales
#   axiz-demo/scripts/test-all.sh --dast    además, el escaneo autenticado de OWASP ZAP de cada contrato
# Los escenarios funcionales leen los logs de los servicios de scripts/demo.sh (servicios en el host); con
# scripts/compose.sh solo se ejecutan las verificaciones de endpoints y la prueba smoke.
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
results=()
run() {
  local name=$1; shift
  printf '\n\033[1m== %s\033[0m\n' "$name"
  if "$@"; then results+=("PASS $name"); else results+=("FAIL $name"); fi
}
run "endpoint checks (curl-tests.sh)" env QUIET=1 bash "$root/scripts/curl-tests.sh"
run "smoke test (smoke.sh)" env SMOKE_LOCAL="$([ -d "$root/.run" ] && echo 1 || echo 0)" bash "$root/scripts/smoke.sh"
if ls "$root"/.run/*.pid >/dev/null 2>&1; then
  run "functional scenarios (scenarios.sh)" bash "$root/scripts/scenarios.sh"
else
  results+=("SKIP functional scenarios: they need the services of scripts/demo.sh up")
fi
[ "${1:-}" = --dast ] && run "OWASP ZAP scan (dast.sh)" bash "$root/scripts/dast.sh"
printf '\n'
printf '%s\n' "${results[@]}"
! printf '%s\n' "${results[@]}" | grep -q '^FAIL'
