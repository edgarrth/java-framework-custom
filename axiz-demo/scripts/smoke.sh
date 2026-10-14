#!/bin/bash
# End-to-end smoke test of the local demo: the customer registers a beneficiary, pays it through
# the channel API, the payment saga (debit, transfer, completion) finishes the order and the
# history read model (payments-history, Kafka -> R2DBC) catches up with it.
# In kind: kubectl -n axiz-demo port-forward svc/beneficiaries-crud 8082:80 (CRUD_URL) and
# svc/payments-history 8086:80 (HISTORY_URL).
set -euo pipefail
kc=http://localhost:8180/realms/axiz/protocol/openid-connect/token
token=$(curl -sf "$kc" -d grant_type=password -d client_id=payments-web -d username=ana \
  -d password=local-only-ana | jq -r .access_token)
auth=(-H "Authorization: Bearer $token" -H "Content-Type: application/json")
key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

beneficiary=$(curl -sf "${auth[@]}" -H "Idempotency-Key: $(key)" \
  "${CRUD_URL:-http://localhost:8082}/v1/customers/C-1001/beneficiaries" \
  -d '{"alias":"Landlord","holderName":"Ana Torres","cci":"00219100123456789053","currency":"PEN"}' \
  | jq -r .id) || beneficiary=$(curl -sf "${auth[@]}" \
  "${CRUD_URL:-http://localhost:8082}/v1/customers/C-1001/beneficiaries" | jq -r '.items[0].id')
echo "beneficiary $beneficiary"

# a different amount per run: the same amount to the same beneficiary on the same day is rejected
# as a probable duplicate (PAYMENTS-PAYMENTSEXPERIENCE-0004)
amount=$(printf '1%02d.%02d' $((RANDOM % 100)) $((RANDOM % 100)))
payment=$(curl -sf "${auth[@]}" -H "Idempotency-Key: $(key)" "${CHANNEL_URL:-http://localhost:8080}/v1/payments" \
  -d "{\"beneficiaryId\":\"$beneficiary\",\"debtorAccount\":\"1910012345678\",\"amount\":$amount,\"currency\":\"PEN\",\"description\":\"Rent\"}")
id=$(echo "$payment" | jq -r .paymentId)
echo "payment $id: $(echo "$payment" | jq -c .)"

for _ in $(seq 1 30); do
  status=$(curl -sf "${auth[@]}" "${CHANNEL_URL:-http://localhost:8080}/v1/payments/$id" | jq -r .status)
  echo "status $status"
  [ "$status" = COMPLETED ] && break
  [ "$status" = REJECTED ] && echo "payment rejected" && exit 1
  sleep 2
done
[ "$status" = COMPLETED ] || { echo "payment did not complete in time"; exit 1; }

# eventually consistent read model: the projection follows the order events
for _ in $(seq 1 30); do
  projected=$(curl -s "${auth[@]}" "${HISTORY_URL:-http://localhost:8086}/v1/payments/$id" | jq -r '.status // empty')
  echo "history ${projected:-not projected yet}"
  [ "$projected" = COMPLETED ] && echo "OK" && exit 0
  sleep 2
done
echo "payment history did not catch up in time"; exit 1
