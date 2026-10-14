#!/bin/bash
# Local demo lifecycle:
#   scripts/demo.sh up      infrastructure + secrets, then start every service in the background
#   scripts/demo.sh down    stop services and infrastructure
#   scripts/demo.sh status  health of every service (UP once started; the first start compiles)
#   scripts/demo.sh smoke   end-to-end check: pay a beneficiary and wait for the order to complete
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
services=(payments-business beneficiaries-crud core-integration payment-process notifications-processor payments-history payments-experience)
case "${1:-}" in
  up)
    docker compose -f "$root/local/compose.yaml" up -d --wait
    bash "$root/scripts/dev-secrets.sh"
    mkdir -p "$root/.run"
    for s in "${services[@]}"; do
      nohup bash "$root/scripts/run.sh" "$s" > "$root/.run/$s.log" 2>&1 &
      echo $! > "$root/.run/$s.pid"
      echo "started $s (log .run/$s.log)"
    done
    ;;
  down)
    for f in "$root"/.run/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; done
    docker compose -f "$root/local/compose.yaml" down -v
    ;;
  status)
    declare -A ports=([payments-experience]=8080 [payments-business]=8081 [beneficiaries-crud]=8082
      [core-integration]=8083 [payment-process]=8084 [notifications-processor]=8085
      [payments-history]=8086)
    ready=0
    for s in "${services[@]}"; do
      health=$(curl -s --max-time 3 "http://localhost:${ports[$s]}/actuator/health" | jq -r '.status // empty' 2>/dev/null || true)
      printf '%-25s %-5s %s\n' "$s" "${ports[$s]}" "${health:-DOWN (see .run/$s.log)}"
      [ "$health" = UP ] && ready=$((ready + 1))
    done
    echo "$ready/${#services[@]} services UP"
    [ "$ready" -eq "${#services[@]}" ]
    ;;
  smoke)
    bash "$root/scripts/smoke.sh"
    ;;
  *) echo "usage: $0 up|down|status|smoke"; exit 2 ;;
esac
