#!/bin/bash
# Local demo lifecycle:
#   scripts/demo.sh up [--attach] [--no-wait] [--build]
#                         checks, infrastructure, secrets, then the 7 services; waits until every
#                         service is UP (or one fails). --attach stays in the terminal showing the
#                         logs of every service, like `docker compose up`; Ctrl+C stops the services
#   scripts/demo.sh logs [service]              follows the logs of every service (or one);
#                                               Ctrl+C only stops following
#   scripts/demo.sh restart <service> [--debug] restarts one service (--debug: Spring web and axiz
#                                               loggers at DEBUG)
#   scripts/demo.sh status                      state of every service (exit 0 only when 7/7 UP)
#   scripts/demo.sh wait [seconds]              waits until 7/7 UP (default 300 s)
#   scripts/demo.sh smoke                       end-to-end check (pays a beneficiary)
#   scripts/demo.sh scenarios [group...]        every functional scenario of the demo (rejections,
#                                               compensations, security, events, notifications,
#                                               audit, observability, batch, DLQ, saga recovery)
#   scripts/demo.sh stop                        stops the services, keeps the infrastructure
#   scripts/demo.sh down                        stops services and infrastructure (and its volumes)
#
# Services run from their executable jars (services/<name>/target/<name>-*.jar). `up` rebuilds a
# jar when it is missing, older than the service sources or older than the axiz framework installed
# in ~/.m2 (AXIZ_M2_REPOSITORY); --build rebuilds all of them.
# AXIZ_JAVA_OPTS adds JVM options (default: faster startup, modest heap).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
run="$root/.run"
services=(payments-business beneficiaries-crud core-integration payment-process notifications-processor payments-history payments-experience)
declare -A ports=([payments-experience]=8080 [payments-business]=8081 [beneficiaries-crud]=8082
  [core-integration]=8083 [payment-process]=8084 [notifications-processor]=8085
  [payments-history]=8086)
# host ports of local/compose.yaml
infra_ports=(5432 6379 9092 4566 8180 8089 3000 4317 4318)

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

# true when something accepts connections on localhost:<port>
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# who holds a port: a container (name and image) or, when ss can tell, a process
port_owner() {
  local owner
  owner=$(docker ps --format '{{.Names}}\t{{.Image}}\t{{.Ports}}' 2>/dev/null \
    | awk -F'\t' -v p=":$1->" 'index($3, p) { print $1 " (container, image " $2 ")"; exit }')
  if [ -z "$owner" ] && command -v ss >/dev/null; then
    owner=$(ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'users:(([^)]*' | head -1 | sed 's/users:((//')
  fi
  [ -n "$owner" ] || owner="a process outside Docker (sudo ss -ltnp \"sport = :$1\" shows which)"
  echo "$owner"
}

pid_of() { [ -f "$run/$1.pid" ] && cat "$run/$1.pid" || true; }
alive() { local pid; pid=$(pid_of "$1"); [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; }

health() {
  curl -s --max-time 3 "http://localhost:${ports[$1]}/actuator/health" 2>/dev/null \
    | jq -r '.status // empty' 2>/dev/null || true
}

# one word per service: UP, DOWN, STARTING, EXITED, BUSY or STOPPED
state() {
  local h; h=$(health "$1")
  if [ "$h" = UP ]; then echo UP
  elif alive "$1"; then if [ -n "$h" ]; then echo "$h"; else echo STARTING; fi
  elif [ -f "$run/$1.pid" ]; then echo EXITED
  elif port_busy "${ports[$1]}"; then echo BUSY
  else echo STOPPED
  fi
}

print_status() {
  local ready=0 s st detail
  for s in "${services[@]}"; do
    st=$(state "$s")
    case "$st" in
      UP) detail="" ; ready=$((ready + 1)) ;;
      STARTING) detail="(starting, log .run/$s.log)" ;;
      EXITED) detail="(the process ended: tail -50 .run/$s.log)" ;;
      BUSY) detail="(port taken by $(port_owner "${ports[$s]}"))" ;;
      STOPPED) detail="(not running: scripts/demo.sh up)" ;;
      *) detail="(health $st: tail -50 .run/$s.log)" ;;
    esac
    printf '%-25s %-5s %-9s %s\n' "$s" "${ports[$s]}" "$st" "$detail"
  done
  echo "$ready/${#services[@]} services UP"
  [ "$ready" -eq "${#services[@]}" ]
}

# waits until 7/7 UP; fails fast when a service process ends
wait_up() {
  local timeout=${1:-300} start=$SECONDS s st ready
  echo "waiting for the services (up to ${timeout}s; the first start takes longer)..."
  while true; do
    ready=0
    for s in "${services[@]}"; do
      st=$(state "$s")
      [ "$st" = UP ] && ready=$((ready + 1))
      if [ "$st" = EXITED ]; then
        echo; red "$s stopped while starting. Last lines of .run/$s.log:"
        tail -40 "$run/$s.log" | sed 's/^/  | /'
        echo "Fix the cause, then: scripts/demo.sh down && scripts/demo.sh up"
        return 1
      fi
    done
    printf '\r  %d/%d UP after %ds ' "$ready" "${#services[@]}" $((SECONDS - start))
    if [ "$ready" -eq "${#services[@]}" ]; then echo; green "all services UP"; return 0; fi
    if [ $((SECONDS - start)) -ge "$timeout" ]; then
      echo; red "not every service is UP after ${timeout}s:"; print_status || true
      return 1
    fi
    sleep 3
  done
}

preflight() {
  local missing=0 tool v
  for tool in docker java curl jq; do
    command -v "$tool" >/dev/null || { red "missing tool: $tool (see README, section 3)"; missing=1; }
  done
  [ "$missing" -eq 0 ] || exit 1
  if ! docker info >/dev/null 2>&1; then
    red "Docker is not running (or this user cannot reach it)."
    echo "  Start Docker Desktop (Windows/macOS, with the WSL integration on) or the daemon on Linux:"
    echo "  sudo service docker start   (WSL without Docker Desktop)   |   sudo systemctl start docker"
    exit 1
  fi
  v=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p' | head -1)
  if [ "${v%%.*}" -lt 25 ] 2>/dev/null; then red "Java $v found; the demo needs JDK 25 (java -version)"; exit 1; fi
}

# ports must be free, or held by this demo
check_ports() {
  local p s owner conflicts=0 kind=""
  for s in "${services[@]}"; do
    p=${ports[$s]}
    if ! alive "$s" && port_busy "$p"; then
      owner=$(port_owner "$p")
      red "port $p ($s) is taken by: $owner"
      case "$owner" in *-control-plane*) kind="${owner%% *}" ;; esac
      conflicts=1
    fi
  done
  for p in "${infra_ports[@]}"; do
    if port_busy "$p"; then
      owner=$(port_owner "$p")
      case "$owner" in
        axiz-demo-*) ;;
        *-control-plane*) red "port $p (infrastructure) is taken by: $owner"; kind="${owner%% *}"; conflicts=1 ;;
        *) red "port $p (infrastructure) is taken by: $owner"; conflicts=1 ;;
      esac
    fi
  done
  if [ "$conflicts" -ne 0 ]; then
    if [ -n "$kind" ]; then
      echo "$kind is the node of the kind cluster '${kind%-control-plane}'. Stop it while you use the local demo:"
      echo "  docker stop $kind        (docker start $kind brings it back)"
      echo "  or delete it: kind delete cluster --name ${kind%-control-plane}"
    fi
    echo "Free those ports and run again. Typical causes:"
    echo "  - a kind cluster publishing 8080:  docker stop <name>-control-plane   (or: kind delete cluster --name <name>)"
    echo "  - another compose project:         docker compose -p <project> down"
    echo "  - a previous run of the services:  scripts/demo.sh down"
    exit 1
  fi
}

jar_of() {
  find "$root/services/$1/target" -maxdepth 1 -name "$1-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    ! -name '*-plain.jar' 2>/dev/null | head -1
}

# the one-shot infrastructure jobs must have finished well: without the Kafka topics the outbox
# relays and the audit publisher wait forever and the payment saga never starts
check_infra() {
  local compose=(docker compose -f "$root/local/compose.yaml") topics t missing=()
  if ! timeout 120 "${compose[@]}" wait kafka-topics >/dev/null 2>&1; then
    red "the Kafka topics were not created. Last lines of the kafka-topics job:"
    "${compose[@]}" logs --no-color --tail 20 kafka-topics 2>&1 | sed 's/^/  | /'
    exit 1
  fi
  topics=$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null || true)
  for t in payments payments.dlq audit; do
    grep -qx "$t" <<<"$topics" || missing+=("$t")
  done
  if [ ${#missing[@]} -gt 0 ]; then
    red "Kafka topics missing: ${missing[*]} (scripts/demo.sh down && scripts/demo.sh up recreates them)"
    exit 1
  fi
  echo "kafka topics ready: payments payments.dlq audit"
}

# rebuilds the jars that are missing or older than their sources or than the installed framework
# (all of them with --build)
m2=${AXIZ_M2_REPOSITORY:-$HOME/.m2/repository}
build() {
  local force=$1 s jar stale=()
  # settlement-batch is a job, not a service: built here, run by scripts/scenarios.sh settlement
  for s in "${services[@]}" settlement-batch; do
    jar=$(jar_of "$s")
    # stale: sources changed, or the framework was installed again (./mvnw install in axiz-framework)
    if [ "$force" = 1 ] || [ -z "$jar" ] \
      || [ -n "$(find "$root/services/$s/src" "$root/services/$s/pom.xml" -type f -newer "$jar" -print -quit)" ] \
      || [ -n "$(find "$m2/pe/axiz" -name '*.jar' -newer "$jar" -print -quit 2>/dev/null)" ]; then
      stale+=("services/$s")
    fi
  done
  [ ${#stale[@]} -eq 0 ] && { echo "jars up to date"; return; }
  echo "building ${stale[*]} (./mvnw package -DskipTests)..."
  (cd "$root" && ./mvnw -q -DskipTests package -pl "$(IFS=,; echo "${stale[*]}")" -am) || {
    red "the build failed; run ./mvnw package -DskipTests in axiz-demo to see the error"
    red "(the framework must be installed first: cd ../axiz-framework && ./mvnw install -DskipTests)"
    exit 1
  }
}

start() {
  local s=$1 jar extra=()
  if alive "$s"; then echo "$s already running (pid $(pid_of "$s"))"; return; fi
  jar=$(jar_of "$s")
  # the reason of every rejected request (4xx) is logged at DEBUG by axiz.errors: visible locally
  extra+=(--logging.level.axiz.errors=DEBUG)
  if [ "${2:-}" = --debug ]; then
    extra+=(--logging.level.org.springframework.web=DEBUG --logging.level.pe.axiz=DEBUG
      --logging.level.axiz=DEBUG)
  fi
  # the working directory is the service folder: local/secrets is read from there
  (
    cd "$root/services/$s"
    export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack accepts any value
    # shellcheck disable=SC2086
    nohup java ${AXIZ_JAVA_OPTS:--XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=10} \
      -jar "$jar" --spring.profiles.active=local,platform "${extra[@]}" > "$run/$s.log" 2>&1 &
    echo $! > "$run/$s.pid"
  )
  echo "started $s on ${ports[$s]} (log .run/$s.log)"
}

stop() {
  local s=$1
  [ -f "$run/$s.pid" ] || return 0
  stop_tree "$(cat "$run/$s.pid")"
  rm -f "$run/$s.pid"
  for _ in $(seq 1 20); do port_busy "${ports[$s]}" || return 0; sleep 1; done
}

# stops the background log followers of this script
unfollow() { pkill -P $$ 2>/dev/null || true; }

# follows the logs of the given services with a colored name per line, like docker compose
# (the caller sets the Ctrl+C trap)
follow() {
  local colors=(36 33 32 35 34 91 92 93) i=0 s c
  for s in "$@"; do
    [ -f "$run/$s.log" ] || touch "$run/$s.log"
    c=${colors[$((i % ${#colors[@]}))]}; i=$((i + 1))
    tail -n "${AXIZ_LOG_LINES:-20}" -F "$run/$s.log" 2>/dev/null \
      | sed -u "s/^/$(printf '\033[%sm%-24s|\033[0m ' "$c" "$s")/" &
  done
  wait
}

# stops a process and its children (older runs used ./mvnw spring-boot:run, which forks the JVM)
stop_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do stop_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}

case "${1:-}" in
  up)
    shift
    wait=1; force=0; attach=0
    for a in "$@"; do
      case "$a" in
        --no-wait) wait=0 ;; --build) force=1 ;; --attach|-a) attach=1 ;;
        *) echo "unknown option $a"; exit 2 ;;
      esac
    done
    preflight
    check_ports
    mkdir -p "$run"
    build "$force"
    docker compose -f "$root/local/compose.yaml" up -d --wait
    check_infra
    bash "$root/scripts/dev-secrets.sh"
    for s in "${services[@]}"; do start "$s"; done
    if [ "$attach" = 1 ]; then
      # like `docker compose up`: logs in the terminal, Ctrl+C stops the services
      trap 'unfollow; echo; echo "stopping the services (the infrastructure keeps running: scripts/demo.sh down removes it)"; for s in "${services[@]}"; do stop "$s"; done; exit 0' INT TERM
      ( wait_up "${AXIZ_UP_TIMEOUT:-300}" >/dev/null 2>&1 \
          && printf '\n\033[32m==== all services UP: scripts/demo.sh smoke in another terminal ====\033[0m\n\n' \
          || printf '\n\033[31m==== not every service is UP: scripts/demo.sh status ====\033[0m\n\n' ) &
      AXIZ_LOG_LINES=1000 follow "${services[@]}"
    elif [ "$wait" = 1 ]; then
      wait_up "${AXIZ_UP_TIMEOUT:-300}"
      echo "next: scripts/demo.sh smoke, then scripts/demo.sh scenarios (scripts/demo.sh logs follows the logs)"
    else
      echo "services starting: scripts/demo.sh wait (or status) before smoke"
    fi
    ;;
  restart)
    svc=${2:?service name}
    [ -n "${ports[$svc]:-}" ] || { echo "unknown service $svc (one of: ${services[*]})"; exit 2; }
    stop "$svc"
    start "$svc" "${3:-}"
    wait_up "${AXIZ_UP_TIMEOUT:-300}"
    ;;
  stop)
    for s in "${services[@]}"; do stop "$s"; done
    echo "services stopped (infrastructure still running; scripts/demo.sh down removes it)"
    ;;
  down)
    for f in "$run"/*.pid; do
      [ -f "$f" ] || continue
      stop_tree "$(cat "$f")"
      rm -f "$f"
    done
    for _ in $(seq 1 20); do
      busy=0; for s in "${services[@]}"; do port_busy "${ports[$s]}" && busy=1; done
      [ "$busy" = 0 ] && break; sleep 1
    done
    docker compose -f "$root/local/compose.yaml" down -v
    ;;
  status)
    print_status
    ;;
  wait)
    wait_up "${2:-300}"
    ;;
  smoke)
    bash "$root/scripts/smoke.sh"
    ;;
  scenarios)
    shift
    bash "$root/scripts/scenarios.sh" "$@"
    ;;
  logs)
    trap 'unfollow; exit 0' INT TERM
    if [ -n "${2:-}" ]; then follow "$2"; else follow "${services[@]}"; fi
    ;;
  *) echo "usage: $0 up [--attach] [--no-wait] [--build] | logs [service] | restart <service> [--debug] | status | wait [seconds] | smoke | scenarios [group...] | stop | down"; exit 2 ;;
esac
