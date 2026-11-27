#!/bin/bash
# Ciclo de vida de la demo local:
#   scripts/demo.sh up [--attach] [--no-wait] [--build]
#                         verificaciones, infraestructura, secretos y luego los 8 servicios; espera a que
#                         todos estén UP (o a que uno falle). --attach permanece en la terminal mostrando
#                         los logs de cada servicio, como `docker compose up`; Ctrl+C detiene los servicios
#   scripts/demo.sh logs [service]              sigue los logs de todos los servicios (o de uno);
#                                               Ctrl+C solo deja de seguirlos
#   scripts/demo.sh restart <service> [--debug] reinicia un servicio (--debug: loggers de Spring web y
#                                               de axiz en DEBUG)
#   scripts/demo.sh status                      estado de cada servicio (salida 0 solo con 8/8 UP)
#   scripts/demo.sh wait [seconds]              espera hasta 8/8 UP (por defecto 300 s)
#   scripts/demo.sh smoke                       verificación de extremo a extremo (paga a un beneficiario)
#   scripts/demo.sh scenarios [group...]        todos los escenarios funcionales de la demo (rechazos,
#                                               compensaciones, seguridad, eventos, notificaciones,
#                                               auditoría, observabilidad, batch, DLQ, recuperación de la saga)
#   scripts/demo.sh stop                        detiene los servicios y conserva la infraestructura
#   scripts/demo.sh down                        detiene los servicios y la infraestructura (y sus volúmenes)
#
# Los servicios se ejecutan desde sus jars ejecutables (services/<name>/target/<name>-*.jar). `up` reconstruye
# un jar (clean package) cuando falta, es más antiguo que el código fuente del servicio o más antiguo que el
# framework axiz instalado en ~/.m2 (AXIZ_M2_REPOSITORY); --build los reconstruye todos.
# AXIZ_JAVA_OPTS añade opciones de la JVM (por defecto: arranque más rápido, heap moderado).
set -Eeuo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
run="$root/.run"
services=(payment-orders beneficiaries banking-core payment-network-gateway payment-process notifications-processor payments-history payments-ux)
declare -A ports=([payments-ux]=8080 [payment-orders]=8081 [beneficiaries]=8082
  [banking-core]=8083 [payment-process]=8084 [notifications-processor]=8085
  [payments-history]=8086 [payment-network-gateway]=8087)
# puertos del host de local/compose.yaml
infra_ports=(5432 6379 9092 4566 8180 8089 3000 4317 4318)

red() { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
# -E: las funciones heredan el trap de ERR, de modo que un fallo inesperado nunca termina el script en silencio
trap 'red "demo.sh: unexpected failure (exit $?) at line $LINENO: $BASH_COMMAND" >&2' ERR

# true cuando algo acepta conexiones en localhost:<port>
port_busy() { (: >"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# lee líneas de `docker ps` (nombre, imagen, puertos, proyecto de compose) e imprime el contenedor que
# publica el puerto del host $1. Docker escribe los rangos como un único mapeo ("0.0.0.0:4317-4318->4317-4318/tcp")
# y los puertos expuestos pero no publicados sin "->" ("5432/tcp"); se tienen en cuenta ambos casos
published_by() {
  awk -F'\t' -v p="$1" '{
    n = split($3, maps, ", ")
    for (i = 1; i <= n; i++) {
      if (index(maps[i], "->") == 0) continue
      host = maps[i]; sub(/->.*/, "", host); sub(/.*:/, "", host)
      lo = host; hi = host
      if (index(host, "-")) { split(host, r, "-"); lo = r[1]; hi = r[2] }
      if (p + 0 >= lo + 0 && p + 0 <= hi + 0) {
        project = ($4 == "") ? "" : ", compose project " $4
        print $1 " (container, image " $2 project ")"; exit
      }
    }
  }'
}

# quién ocupa un puerto: un contenedor (nombre, imagen y proyecto de compose) o, cuando ss puede indicarlo, un proceso
port_owner() {
  local owner
  owner=$(docker ps --format '{{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Label "com.docker.compose.project"}}' \
    2>/dev/null | published_by "$1")
  if [ -z "$owner" ] && command -v ss >/dev/null; then
    owner=$(ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'users:(([^)]*' | head -1 | sed 's/users:((//' || true)
  fi
  [ -n "$owner" ] || owner="a process outside Docker (sudo ss -ltnp \"sport = :$1\" shows which)"
  echo "$owner"
}

pid_of() { if [ -f "$run/$1.pid" ]; then cat "$run/$1.pid" || true; fi; }
alive() { local pid; pid=$(pid_of "$1"); [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; }

health() {
  curl -s --max-time 3 "http://localhost:${ports[$1]}/actuator/health" 2>/dev/null \
    | jq -r '.status // empty' 2>/dev/null || true
}

# una palabra por servicio: UP, DOWN, STARTING, EXITED, BUSY o STOPPED
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

# espera hasta 8/8 UP; falla rápido cuando termina el proceso de un servicio
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
      # nunca arrancó (sin proceso, nada en su puerto): esperar no sirve de nada
      if [ "$st" = STOPPED ]; then
        echo; red "$s is not running: start the services with scripts/demo.sh up"
        print_status || true
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

# los puertos deben estar libres u ocupados por esta demo
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
        *", compose project axiz-demo)") ;; # la infraestructura de esta demo, en ejecución
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

# el jar de un servicio, o nada (checkout recién clonado: sin target/ todavía)
jar_of() {
  [ -d "$root/services/$1/target" ] || return 0
  find "$root/services/$1/target" -maxdepth 1 -name "$1-*.jar" ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
    ! -name '*-plain.jar' 2>/dev/null | head -1
}

# los tópicos de Kafka deben existir antes de que arranquen los servicios: sin ellos los relays del outbox y
# el publicador de auditoría esperan indefinidamente y la saga de pago nunca arranca. La verificación consulta
# al broker por los tópicos (lo que importa), no el estado del job kafka-topics: `docker compose wait` sobre un job
# ya terminado se comporta distinto según la versión de Compose y fallaba con todos los tópicos creados
check_infra() {
  local compose=(docker compose -f "$root/local/compose.yaml") topics t missing deadline state
  deadline=$((SECONDS + 120))
  while :; do
    topics=$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null || true)
    missing=()
    for t in payments payments.dlq audit; do
      grep -qx "$t" <<<"$topics" || missing+=("$t")
    done
    [ ${#missing[@]} -eq 0 ] && break
    state=$("${compose[@]}" ps -a --format '{{.State}} {{.ExitCode}}' kafka-topics 2>/dev/null | head -1 || true)
    if [ "${state%% *}" = exited ] && [ "${state##* }" != 0 ] || [ $SECONDS -ge $deadline ]; then
      red "Kafka topics missing: ${missing[*]} (kafka-topics job: ${state:-not found}). Last lines of the job:"
      "${compose[@]}" logs --no-color --tail 20 kafka-topics 2>&1 | sed 's/^/  | /'
      echo "scripts/demo.sh down && scripts/demo.sh up recreates them"
      exit 1
    fi
    sleep 2
  done
  echo "kafka topics ready: payments payments.dlq audit"
}

# reconstruye los jars que faltan o que son más antiguos que su código fuente o que el framework instalado
# (todos con --build)
m2=${AXIZ_M2_REPOSITORY:-$HOME/.m2/repository}
build() {
  local force=$1 s jar stale=()
  # settlement-batch es un job, no un servicio: se construye aquí y lo ejecuta scripts/scenarios.sh settlement
  for s in "${services[@]}" settlement-batch; do
    jar=$(jar_of "$s")
    # desactualizado: cambió el código fuente o se volvió a instalar el framework (./mvnw install en axiz-framework).
    # Los directorios también cuentan: eliminar o renombrar un archivo solo modifica su directorio
    if [ "$force" = 1 ] || [ -z "$jar" ] \
      || [ -n "$(find "$root/services/$s/src" "$root/services/$s/pom.xml" -newer "$jar" -print -quit)" ] \
      || [ -n "$(find "$m2/pe/axiz" -name '*.jar' -newer "$jar" -print -quit 2>/dev/null)" ]; then
      stale+=("services/$s")
    fi
  done
  [ ${#stale[@]} -eq 0 ] && { echo "jars up to date"; return; }
  # clean: un package incremental conserva en target/ el .class de un fuente renombrado o eliminado y
  # el jar lo incluiría (p. ej. dos clases @Configuration que definen el mismo bean)
  echo "building ${stale[*]} (./mvnw clean package -DskipTests)..."
  (cd "$root" && ./mvnw -q -DskipTests clean package -pl "$(IFS=,; echo "${stale[*]}")" -am) || {
    red "the build failed; run ./mvnw clean package -DskipTests in axiz-demo to see the error"
    red "(the framework must be installed first: cd ../axiz-framework && ./mvnw install -DskipTests)"
    exit 1
  }
}

start() {
  local s=$1 jar extra=()
  if alive "$s"; then echo "$s already running (pid $(pid_of "$s"))"; return; fi
  jar=$(jar_of "$s")
  [ -n "$jar" ] || { red "no jar in services/$s/target: scripts/demo.sh up --build compiles it"; exit 1; }
  # axiz.errors registra en DEBUG el motivo de cada petición rechazada (4xx): visible en local
  extra+=(--logging.level.axiz.errors=DEBUG)
  if [ "${2:-}" = --debug ]; then
    extra+=(--logging.level.org.springframework.web=DEBUG --logging.level.pe.axiz=DEBUG
      --logging.level.axiz=DEBUG)
  fi
  # el directorio de trabajo es la carpeta del servicio: local/secrets se lee desde allí
  (
    cd "$root/services/$s"
    export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack acepta cualquier valor
    # shellcheck disable=SC2086
    nohup java -Djava.security.egd=file:/dev/./urandom --enable-native-access=ALL-UNNAMED ${AXIZ_JAVA_OPTS:--XX:TieredStopAtLevel=1 -XX:MaxRAMPercentage=10} \
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

# detiene los procesos en segundo plano de este script que siguen los logs
unfollow() { pkill -P $$ 2>/dev/null || true; }

# sigue los logs de los servicios indicados con un nombre coloreado por línea, como docker compose
# (quien invoca define el trap de Ctrl+C)
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

# detiene un proceso y sus hijos (por si se arrancó con ./mvnw spring-boot:run, que hace fork de la JVM)
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
    bash "$root/scripts/dev-secrets.sh" || exit 1
    for s in "${services[@]}"; do start "$s"; done
    if [ "$attach" = 1 ]; then
      # como `docker compose up`: logs en la terminal, Ctrl+C detiene los servicios
      trap 'unfollow; echo; echo "stopping the services (the infrastructure keeps running: scripts/demo.sh down removes it)"; for s in "${services[@]}"; do stop "$s"; done; exit 0' INT TERM
      ( wait_up "${AXIZ_UP_TIMEOUT:-300}" >/dev/null 2>&1 \
          && printf '\n\033[32m==== all services UP: scripts/demo.sh smoke in another terminal ====\033[0m\n\n' \
          || printf '\n\033[31m==== not every service is UP: scripts/demo.sh status ====\033[0m\n\n' ) &
      AXIZ_LOG_LINES=1000 follow "${services[@]}"
    elif [ "$wait" = 1 ]; then
      wait_up "${AXIZ_UP_TIMEOUT:-300}" || exit 1
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
    wait_up "${AXIZ_UP_TIMEOUT:-300}" || exit 1
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
    # código de salida 1 salvo que todos los servicios estén UP (la tabla explica el motivo)
    print_status || exit 1
    ;;
  wait)
    wait_up "${2:-300}" || exit 1
    ;;
  smoke)
    exec bash "$root/scripts/smoke.sh"
    ;;
  scenarios)
    shift
    exec bash "$root/scripts/scenarios.sh" "$@"
    ;;
  logs)
    trap 'unfollow; exit 0' INT TERM
    if [ -n "${2:-}" ]; then follow "$2"; else follow "${services[@]}"; fi
    ;;
  *) echo "usage: $0 up [--attach] [--no-wait] [--build] | logs [service] | restart <service> [--debug] | status | wait [seconds] | smoke | scenarios [group...] | stop | down"; exit 2 ;;
esac
