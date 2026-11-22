#!/bin/bash
# Whole demo in Docker Compose: infrastructure (local/compose.yaml) and the eight services as
# containers (local/compose.apps.yaml, profile "apps"), from any directory:
#   axiz-demo/scripts/compose.sh up [--build|--no-build]   start everything and print the routes
#   axiz-demo/scripts/compose.sh routes                    routes of every service
#   axiz-demo/scripts/compose.sh status                    health of every service
#   axiz-demo/scripts/compose.sh logs [service]            follow the logs (Ctrl+C stops following)
#   axiz-demo/scripts/compose.sh down                      stop and remove containers and volumes
# --build rebuilds the service images (jar and Jib image, tests skipped); --no-build uses the
# images already built and fails when one is missing. Without either, only missing images are
# built. Images need the framework installed in ~/.m2 (scripts/build.sh at the repository root).
set -Eeuo pipefail
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
services=(payment-orders beneficiaries banking-core payment-network-gateway payment-process notifications-processor payments-history payments-ux)
declare -A ports=([payments-ux]=8080 [payment-orders]=8081 [beneficiaries]=8082
  [banking-core]=8083 [payment-process]=8084 [notifications-processor]=8085
  [payments-history]=8086 [payment-network-gateway]=8087)
# contract of each REST service (notifications-processor only consumes events)
declare -A contracts=([payments-ux]=payments-ux [payment-orders]=payment-orders
  [beneficiaries]=beneficiaries [banking-core]=banking-core [payment-process]=payment-process
  [payments-history]=payments-history [payment-network-gateway]=payment-network-gateway)
export AXIZ_UID="${AXIZ_UID:-$(id -u)}" AXIZ_GID="${AXIZ_GID:-$(id -g)}"
compose=(docker compose -f "$root/local/compose.yaml" -f "$root/local/compose.apps.yaml" --profile apps)

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
for tool in docker curl jq; do command -v "$tool" >/dev/null || { red "missing tool: $tool"; exit 1; }; done

image_exists() { docker image inspect "axiz/$1:local" >/dev/null 2>&1; }

build_images() {
  local mode=$1 s
  for s in "${services[@]}"; do
    if [ "$mode" = always ] || ! image_exists "$s"; then
      [ "$mode" = never ] && { red "image axiz/$s:local is missing: run with --build"; exit 1; }
      echo "building image axiz/$s:local"
      (cd "$root/services/$s" && ./mvnw -q -B -ntp -DskipTests package \
        -Daxiz.image.repository="axiz/$s" -Daxiz.image.tag=local jib:dockerBuild)
    fi
  done
}

health() {
  curl -s --max-time 3 "http://localhost:${ports[$1]}/actuator/health" 2>/dev/null \
    | jq -r '.status // empty' 2>/dev/null || true
}

status() {
  local s up=0
  for s in "${services[@]}"; do
    local h; h=$(health "$s")
    printf '  %-25s %-6s http://localhost:%s\n' "$s" "${h:-DOWN}" "${ports[$s]}"
    [ "$h" = UP ] && up=$((up + 1))
  done
  echo "$up/${#services[@]} UP"
  [ "$up" = "${#services[@]}" ]
}

wait_up() {
  local deadline=$((SECONDS + ${1:-300})) s up
  echo "waiting for the services (up to ${1:-300}s)..."
  while :; do
    up=0
    for s in "${services[@]}"; do [ "$(health "$s")" = UP ] && up=$((up + 1)); done
    printf '\r  %s/%s UP after %ss ' "$up" "${#services[@]}" "$SECONDS"
    [ "$up" = "${#services[@]}" ] && { echo; green "all services UP"; return 0; }
    [ $SECONDS -ge $deadline ] && { echo; red "not every service is UP"; status || true; return 1; }
    sleep 3
  done
}

# METHOD path for every operation of a contract (paths at two spaces, methods at four)
routes() {
  local s
  for s in "${services[@]}"; do
    [ -n "${contracts[$s]:-}" ] || continue
    printf '\n%s (http://localhost:%s)\n' "$s" "${ports[$s]}"
    awk '/^paths:/{p=1; next} /^[a-z]/{p=0}
         p && /^  \/[^:]*:/{path=$1; sub(/:$/, "", path)}
         p && /^    (get|post|put|patch|delete):/{m=$1; sub(/:$/, "", m); printf "  %-7s http://localhost:%s%s\n", toupper(m), port, path}' \
      port="${ports[$s]}" "$root/contracts/${contracts[$s]}.yaml"
  done
  printf '\nnotifications-processor (http://localhost:8085): consumes events only (actuator)\n'
  printf 'keycloak http://localhost:8180  grafana http://localhost:3000  localstack http://localhost:4566  wiremock http://localhost:8089\n'
}

case "${1:-}" in
  up)
    mode=missing
    case "${2:-}" in --build) mode=always ;; --no-build) mode=never ;; "") ;; *) red "unknown option $2"; exit 2 ;; esac
    build_images "$mode"
    bash "$root/scripts/dev-secrets.sh"
    "${compose[@]}" up -d --build
    wait_up 300 || { red "see the logs: axiz-demo/scripts/compose.sh logs <service>"; exit 1; }
    routes
    ;;
  routes) routes ;;
  status) status || exit 1 ;;
  logs) shift; "${compose[@]}" logs -f "$@" ;;
  down) "${compose[@]}" down -v --remove-orphans ;;
  *) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
