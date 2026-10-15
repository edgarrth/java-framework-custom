#!/bin/bash
# Whole demo inside a local kind cluster, with the same Helm charts the environments use:
#   kind/up.sh          create the cluster, build and load the images, deploy everything
#   kind/up.sh down     delete the cluster
# Needs docker, kind, kubectl, helm and jq. The channel API ends up on http://localhost:9080,
# Keycloak on http://localhost:9180 and Grafana on http://localhost:9300 (the local demo of
# scripts/demo.sh uses 8080/8180/3000, so both can run at the same time).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); root=$(dirname "$here")
services=(payments-business beneficiaries-crud core-integration payment-process notifications-processor payments-history payments-experience settlement-batch)
ns=axiz-demo

if [ "${1:-}" = down ]; then kind delete cluster --name axiz-demo; exit 0; fi

kind get clusters | grep -qx axiz-demo || kind create cluster --config "$here/cluster.yaml"

# images: Jib to the local Docker daemon, then into the kind node
for s in "${services[@]}"; do
  (cd "$root/services/$s" && ./mvnw -q -DskipTests -Daxiz.image.repository="axiz/$s" -Daxiz.image.tag=local jib:dockerBuild)
  kind load docker-image "axiz/$s:local" --name axiz-demo
done

# infrastructure stand-ins
kubectl apply -k "$here/deps"
kubectl -n $ns wait --for=condition=available deploy --all --timeout=300s
kubectl -n $ns wait --for=condition=complete job/kafka-topics --timeout=300s

# secrets: the charts mount <service>-secrets; in kind they come from the dev-secrets files
bash "$root/scripts/dev-secrets.sh"
for s in "${services[@]}"; do
  dir="$root/services/$s/local/secrets"
  if [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then
    kubectl -n $ns create secret generic "$s-secrets" --from-file="$dir" --dry-run=client -o yaml | kubectl apply -f -
  fi
done

# services, with the chart of each one and its kind values
for s in "${services[@]}"; do
  helm upgrade --install "$s" "$root/services/$s/deploy/helm/$s" -n $ns -f "$here/values/$s.yaml" --wait --timeout 10m
done

# run the settlement job once now (it is a CronJob)
kubectl -n $ns create job --from=cronjob/settlement-batch settlement-batch-now || true
echo "demo ready. End-to-end payment:"
echo "  kubectl -n $ns port-forward svc/beneficiaries-crud 9082:80 &"
echo "  kubectl -n $ns port-forward svc/payments-history 9086:80 &"
echo "  SMOKE_LOCAL=0 KC_URL=http://localhost:9180 CHANNEL_URL=http://localhost:9080 CRUD_URL=http://localhost:9082 HISTORY_URL=http://localhost:9086 scripts/smoke.sh"
