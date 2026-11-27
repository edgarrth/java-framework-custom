#!/bin/bash
# Toda la demo dentro de un clúster kind local, con los mismos charts de Helm que usan los entornos:
#   kind/up.sh          crea el clúster, construye y carga las imágenes y despliega todo
#   kind/up.sh down     elimina el clúster
# Requiere docker, kind, kubectl, helm y jq. La API del canal queda en http://localhost:9080,
# Keycloak en http://localhost:9180 y Grafana en http://localhost:9300 (la demo local de
# scripts/demo.sh usa 8080/8180/3000, así que ambas pueden ejecutarse a la vez).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); root=$(dirname "$here")
services=(payment-orders beneficiaries banking-core payment-network-gateway payment-process notifications-processor payments-history payments-ux settlement-batch)
ns=axiz-demo

if [ "${1:-}" = down ]; then kind delete cluster --name axiz-demo; exit 0; fi

kind get clusters | grep -qx axiz-demo || kind create cluster --config "$here/cluster.yaml"

# imágenes: Jib al daemon local de Docker y luego al nodo de kind
for s in "${services[@]}"; do
  (cd "$root/services/$s" && ./mvnw -q -DskipTests -Daxiz.image.repository="axiz/$s" -Daxiz.image.tag=local jib:dockerBuild)
  kind load docker-image "axiz/$s:local" --name axiz-demo
done

# sustitutos de infraestructura
docker compose -f "$root/local/compose.yaml" build postgres valkey kafka wiremock keycloak localstack otel-lgtm
kind load docker-image axiz-local/postgres:17.12-r2 axiz-local/valkey:8.1.8-r1 axiz-local/kafka:4.2.2-r1 axiz-local/wiremock:3.13.2-r1 axiz-local/keycloak:26.8.2-r1 axiz-local/localstack:4.14.0-r1 axiz-local/otel-lgtm:2026.11-r1 --name axiz-demo
kubectl apply -k "$here/deps"
kubectl -n $ns wait --for=condition=available deploy --all --timeout=300s
kubectl -n $ns wait --for=condition=complete job/kafka-topics --timeout=300s

# secretos: los charts montan <service>-secrets; en kind provienen de los archivos de dev-secrets
# configuración compartida por todos los servicios del namespace (la monta cada chart, opcional)
kubectl -n $ns apply -f "$root/deploy/platform/axiz-common.yaml"

bash "$root/scripts/dev-secrets.sh"
for s in "${services[@]}"; do
  dir="$root/services/$s/local/secrets"
  if [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then
    kubectl -n $ns create secret generic "$s-secrets" --from-file="$dir" --dry-run=client -o yaml | kubectl apply -f -
  fi
done

# servicios, con el chart de cada uno y sus valores para kind
for s in "${services[@]}"; do
  helm upgrade --install "$s" "$root/services/$s/deploy/helm/$s" -n $ns -f "$here/values/$s.yaml" --wait --timeout 10m
done

# se ejecuta una vez el job de liquidación (es un CronJob)
kubectl -n $ns create job --from=cronjob/settlement-batch settlement-batch-now || true
echo "demo ready. End-to-end payment:"
echo "  kubectl -n $ns port-forward svc/beneficiaries 9082:80 &"
echo "  kubectl -n $ns port-forward svc/payments-history 9086:80 &"
echo "  SMOKE_LOCAL=0 KC_URL=http://localhost:9180 CHANNEL_URL=http://localhost:9080 CRUD_URL=http://localhost:9082 HISTORY_URL=http://localhost:9086 scripts/smoke.sh"
