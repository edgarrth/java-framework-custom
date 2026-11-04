#!/bin/bash
# End-to-end smoke test of the demo: the customer registers a beneficiary, pays it through the
# channel API, the payment saga (debit, transfer, completion) finishes the order and the history
# read model (payments-history, Kafka -> R2DBC) catches up with it.
#
# Local (scripts/demo.sh up): waits until the 8 services are UP, then runs.
# Elsewhere, point it at the deployment and skip the local wait:
#   SMOKE_LOCAL=0 KC_URL=... CHANNEL_URL=... CRUD_URL=... HISTORY_URL=... scripts/smoke.sh
# Every step prints what it checks; a failing step prints the HTTP status and body and exits 1.
set -Eeuo pipefail
# -E: functions inherit the ERR trap, so an unexpected failure never ends the script silently
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
root=$(cd "$(dirname "$0")/.." && pwd)
KC_URL=${KC_URL:-http://localhost:8180}
CHANNEL_URL=${CHANNEL_URL:-http://localhost:8080}
CRUD_URL=${CRUD_URL:-http://localhost:8082}
HISTORY_URL=${HISTORY_URL:-http://localhost:8086}
WAIT=${SMOKE_WAIT:-300}

fail() { printf '\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
for tool in curl jq; do command -v "$tool" >/dev/null || fail "missing tool: $tool"; done
key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

# services whose logs explain a failure of the current step (local runs: .run/<service>.log)
involved=()
# prints the last warnings and errors of the involved services
show_logs() {
  [ "${SMOKE_LOCAL:-1}" = 1 ] || return 0
  local s f
  for s in "${involved[@]}"; do
    f="$root/.run/$s.log"
    [ -f "$f" ] || continue
    printf '\n-- last warnings, errors and failed calls of %s (%s)\n' "$s" ".run/$s.log" >&2
    # warnings and errors, plus the requests it answered and the calls it made with a 4xx/5xx
    # status (or none: status=-1), which show which service failed along the way
    grep -E ' (WARN|ERROR) |Exception|Caused by|status=(-1|[45][0-9]{2})\b' "$f" \
      | tail -n "${SMOKE_LOG_LINES:-15}" | cut -c1-400 >&2 || true
  done
}

# call METHOD URL [curl args...]: sets $code, $body and, when there is no answer, $why
call() {
  local method=$1 url=$2; shift 2
  local out rc=0 err
  err=$(mktemp)
  out=$(curl -sS --max-time "${SMOKE_TIMEOUT:-30}" -w '\n%{http_code}' -X "$method" "$url" "$@" 2>"$err") || rc=$?
  why=$(cat "$err"); rm -f "$err"
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  if [ "$rc" -ne 0 ] || ! [[ "$code" =~ ^[1-9][0-9]{2}$ ]]; then
    code=000
    case "$rc" in
      7) why="connection refused: nothing listens on $url" ;;
      28) why="no response in ${SMOKE_TIMEOUT:-30}s ($why)" ;;
      52) why="the service closed the connection without answering ($why)" ;;
      56) why="the connection broke while receiving the answer ($why)" ;;
      *) why="${why:-curl exit $rc}" ;;
    esac
  fi
}
# expect <codes> <what>: fails with the response (and the logs of the involved services)
expect() {
  [[ " $1 " == *" $code "* ]] && return 0
  if [ "$code" = 000 ]; then
    show_logs
    fail "$2: no answer: $why"
  fi
  show_logs
  fail "$2: HTTP $code
$(echo "$body" | jq . 2>/dev/null || echo "$body")"
}

if [ "${SMOKE_LOCAL:-1}" = 1 ]; then
  step "services (scripts/demo.sh wait $WAIT)"
  bash "$root/scripts/demo.sh" wait "$WAIT" || fail "the services are not UP; see the status above"
fi

step "token of the customer ana (Keycloak $KC_URL)"
involved=()
call POST "$KC_URL/realms/axiz/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=payments-web -d username=ana -d password=local-only-ana
expect 200 "token request"
token=$(echo "$body" | jq -r .access_token)
echo "token ok (${#token} chars)"
auth=(-H "Authorization: Bearer $token" -H "Content-Type: application/json")

step "beneficiary of C-1001 (beneficiaries $CRUD_URL)"
involved=(beneficiaries)
call POST "$CRUD_URL/v1/customers/C-1001/beneficiaries" "${auth[@]}" -H "Idempotency-Key: $(key)" \
  -d '{"alias":"Landlord","holderName":"Ana Torres","cci":"00219100123456789053","currency":"PEN"}'
if [ "$code" = 201 ] || [ "$code" = 200 ]; then
  beneficiary=$(echo "$body" | jq -r .id)
  echo "registered $beneficiary"
else
  # already registered by an earlier run (409): reuse it
  [ "$code" = 409 ] || expect "201 409" "register beneficiary"
  call GET "$CRUD_URL/v1/customers/C-1001/beneficiaries" "${auth[@]}"
  expect 200 "list beneficiaries"
  beneficiary=$(echo "$body" | jq -r '.items[0].id // empty')
  [ -n "$beneficiary" ] || fail "no beneficiary to reuse: $body"
  echo "reusing $beneficiary"
fi

step "payment through the channel API (payments-ux $CHANNEL_URL)"
involved=(payments-ux beneficiaries payment-orders)
# a different amount per run: the same amount to the same beneficiary on the same day is rejected
# as a probable duplicate (PAYMENTS-PAYMENTSEXPERIENCE-0004)
amount=$(printf '1%02d.%02d' $((RANDOM % 100)) $((RANDOM % 100)))
call POST "$CHANNEL_URL/v1/payments" "${auth[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"beneficiaryId\":\"$beneficiary\",\"debtorAccount\":\"1910012345678\",\"amount\":$amount,\"currency\":\"PEN\",\"description\":\"Rent\"}"
expect "201 202" "create payment"
id=$(echo "$body" | jq -r .paymentId)
echo "payment $id: $(echo "$body" | jq -c .)"

step "saga: waiting for COMPLETED"
involved=(payments-ux payment-orders payment-process banking-core)
status=""
for _ in $(seq 1 "${SMOKE_POLLS:-60}"); do
  call GET "$CHANNEL_URL/v1/payments/$id" "${auth[@]}"
  expect 200 "get payment"
  status=$(echo "$body" | jq -r .status)
  echo "status $status"
  [ "$status" = COMPLETED ] && break
  [ "$status" = REJECTED ] && show_logs && fail "payment rejected: $(echo "$body" | jq -c .)
  check WireMock (http://localhost:8089/__admin/requests) and .run/banking-core.log, .run/payment-process.log"
  sleep "${SMOKE_POLL:-2}"
done
# RECEIVED: the saga never started. Shows whether payment-process consumes the payments topic
# (consumer group, lag) and whether the event reached the topic
kafka_state() {
  [ "${SMOKE_LOCAL:-1}" = 1 ] && command -v docker >/dev/null || return 0
  local compose=(docker compose -f "$root/local/compose.yaml") bin=/opt/kafka/bin
  printf '\n-- consumer group payment-process (no members or LAG > 0: the event is not being consumed)\n' >&2
  "${compose[@]}" exec -T kafka "$bin/kafka-consumer-groups.sh" --bootstrap-server localhost:9092 \
    --describe --group payment-process 2>&1 | tail -n 8 >&2 || true
  printf '\n-- messages in topic payments (end offsets per partition)\n' >&2
  "${compose[@]}" exec -T kafka "$bin/kafka-get-offsets.sh" --bootstrap-server localhost:9092 \
    --topic payments 2>&1 | tail -n 5 >&2 || true
  if [ -f "$root/.run/payment-process.log" ] \
    && ! grep -q 'ConsumerConfig values' "$root/.run/payment-process.log"; then
    printf '\npayment-process created no Kafka consumer: reinstall the framework (README 4.1)\n' >&2
  fi
}
if [ "$status" != COMPLETED ]; then
  show_logs
  [ "$status" = RECEIVED ] && kafka_state
  fail "payment still $status after $(( ${SMOKE_POLLS:-60} * 2 ))s; see .run/payment-process.log and .run/payment-orders.log"
fi

step "history read model (payments-history $HISTORY_URL)"
involved=(payments-history)
projected=""
for _ in $(seq 1 "${SMOKE_POLLS:-60}"); do
  call GET "$HISTORY_URL/v1/payments/$id" "${auth[@]}"
  [ "$code" = 200 ] || [ "$code" = 404 ] || expect 200 "get history"
  projected=$(echo "$body" | jq -r '.status // empty' 2>/dev/null || true)
  echo "history ${projected:-not projected yet}"
  [ "$projected" = COMPLETED ] && break
  sleep "${SMOKE_POLL:-2}"
done
[ "$projected" = COMPLETED ] || { show_logs; fail "payment history did not catch up in $(( ${SMOKE_POLLS:-60} * 2 ))s; see .run/payments-history.log"; }

printf '\n\033[32mOK\033[0m: payment %s completed and projected\n' "$id"
