#!/bin/bash
# Development-only stack. Backend ports stay inside this container; Compose binds public ports to loopback.
set -euo pipefail
pids=()
cleanup() {
  trap - EXIT INT TERM
  if ((${#pids[@]})); then
    kill -TERM "${pids[@]}" 2>/dev/null || true
    wait "${pids[@]}" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 0' INT TERM

mkdir -p /data/grafana/plugins /data/grafana/logs /data/loki /data/tempo /data/prometheus
grafana server --homepath=/otel-lgtm/grafana --config=/otel-lgtm/grafana.ini &
pids+=("$!")
loki -config.file=/otel-lgtm/loki.yaml &
pids+=("$!")
tempo -config.file=/otel-lgtm/tempo.yaml &
pids+=("$!")
prometheus --config.file=/otel-lgtm/prometheus.yaml --storage.tsdb.path=/data/prometheus \
  --web.listen-address=127.0.0.1:9090 --web.enable-otlp-receiver \
  --storage.tsdb.retention.time=24h &
pids+=("$!")
otelcol --config=/otel-lgtm/collector.yaml &
pids+=("$!")

# A partial stack is unhealthy: terminate the remaining processes if any component exits.
wait -n "${pids[@]}"
exit 1
