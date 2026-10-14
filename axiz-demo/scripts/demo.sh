#!/bin/bash
# Local demo lifecycle:
#   scripts/demo.sh up      infrastructure + secrets, then start every service in the background
#   scripts/demo.sh down    stop services and infrastructure
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
  smoke)
    bash "$root/scripts/smoke.sh"
    ;;
  *) echo "usage: $0 up|down|smoke"; exit 2 ;;
esac
