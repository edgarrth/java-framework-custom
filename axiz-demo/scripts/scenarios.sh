#!/bin/bash
# "condition && pass ... || bad ...": pass always succeeds, so bad runs only when the condition failed
# shellcheck disable=SC2015
# Functional scenarios of the whole demo, against the services started by scripts/demo.sh up.
# Where the smoke test pays once, this suite checks what the demo promises, one group at a time:
#
#   beneficiaries  registration, CCI check digits, duplicates, rename, soft delete, cursor pages,
#                  masking in the channel
#   payment        a payment end to end: channel, saga, process view, history, order
#   idempotency    replayed request, same key with another body, missing key
#   rules          duplicate payment, daily limit, currency flag (with i18n), currency mismatch
#   saga           rejections and compensations: no funds, blocked account, closed destination
#                  (the debit is reversed), account of another customer
#   security       401 without token, other customer's data (404/403), status changes and money
#                  movements reserved to payment-process, protected actuator
#   integration    core-integration: SOAP credit, unknown movement, holder check
#   events         outbox published, encrypted accounts, saga states, Kafka consumers and DLQ,
#                  CloudEvents headers
#   notifications  notification requests on SNS for completed and rejected payments
#   audit          audit trail: SQS -> S3 archive (with manifest), Kafka audit topic, audit DLQ
#   observability  metrics in Prometheus and traces in Tempo (through Grafana): one trace from the
#                  channel request to the saga
#   settlement     settlement-batch: loads a new file per run, idempotent rerun, summary on S3,
#                  a lost summary written again by a rerun
#   dlq            a malformed message on the payments topic ends in payments.dlq (each consumer)
#   recovery       a debit that times out leaves the saga VERIFYING; the recovery job completes it
#                  (takes about 4 minutes)
#
#   scripts/scenarios.sh                  every group, in that order (about 8 minutes)
#   scripts/scenarios.sh saga security    only those groups (data they need is created)
#   SCENARIOS_SKIP="recovery" scripts/scenarios.sh   every group but those
#
# Every check prints PASS or FAIL; a failing check shows why (status, body) and the last
# warnings and errors of the services involved. The summary lists the failures; exit code 1 when
# any check failed. Local runs only: it reads the databases, Kafka, LocalStack and WireMock of
# local/compose.yaml.
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC_URL=${KC_URL:-http://localhost:8180}
EXP=http://localhost:8080
BUS=http://localhost:8081
CRUD=http://localhost:8082
CORE=http://localhost:8083
PROC=http://localhost:8084
HIST=http://localhost:8086
WM=http://localhost:8089
GRAFANA=${GRAFANA_URL:-http://localhost:3000}
compose=(docker compose -f "$root/local/compose.yaml")
JSON=(-H "Content-Type: application/json")
all_groups=(beneficiaries payment idempotency rules saga security integration events notifications
  audit observability settlement dlq recovery)

# accounts and CCIs of the simulated core and network (local/wiremock)
ACCOUNT=1910012345678         # C-1001 (ana) is its holder
NO_FUNDS=1910099999999        # CB-51
BLOCKED=1910088888888         # CB-62
SLOW=1910077777777            # the recovery group makes its debits time out
CCI_LANDLORD=00219100123456789053
CCI_CLOSED=00219100123456780052   # the network rejects transfers to it (NET-R02)
CCI_USD=00219100123456781159
CCI_TEMP=00219100123456782256
CCI_LUIS=00319100987654321043

passed=0
failed=0
failures=()
group=""
involved=()
run_ids=()      # payments created by this run
used_amounts=" "

# ---------------------------------------------------------------------------------------------
# output

pass() { passed=$((passed + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; return 0; }
bad() {
  failed=$((failed + 1))
  failures+=("[$group] $1")
  printf '  \033[31mFAIL\033[0m %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | head -n 20 | sed 's/^/         /'
  show_logs
}
note() { printf '       %s\n' "$*"; }

# last warnings and errors of the services involved in the current group
show_logs() {
  local s f
  for s in "${involved[@]}"; do
    f="$root/.run/$s.log"
    [ -f "$f" ] || continue
    printf '         -- last warnings/errors of %s (.run/%s.log)\n' "$s" "$s"
    grep -E ' (WARN|ERROR) |Exception|Caused by|status=(-1|5[0-9]{2})\b' "$f" \
      | tail -n "${SCENARIOS_LOG_LINES:-6}" | cut -c1-300 | sed 's/^/           /' || true
  done
}

# ---------------------------------------------------------------------------------------------
# HTTP

key() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }

# call METHOD URL [curl args...]: sets $code, $body and $headers
call() {
  local method=$1 url=$2 out hdr rc=0
  shift 2
  hdr=$(mktemp)
  out=$(curl -sS --max-time "${SCENARIOS_TIMEOUT:-30}" -D "$hdr" -w '\n%{http_code}' -X "$method" "$url" "$@" 2>&1) || rc=$?
  headers=$(tr -d '\r' < "$hdr"); rm -f "$hdr"
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  if [ "$rc" -ne 0 ] || ! [[ "$code" =~ ^[1-9][0-9]{2}$ ]]; then code=000; fi
}

# expect NAME "<codes>" [jq conditions on the body...]
expect() {
  local name=$1 codes=$2 q why=""
  shift 2
  [[ " $codes " == *" $code "* ]] || why="HTTP $code, expected $codes"
  if [ -z "$why" ]; then
    for q in "$@"; do
      jq -e "$q" >/dev/null 2>&1 <<<"$body" || { why="response does not satisfy: $q"; break; }
    done
  fi
  if [ -z "$why" ]; then pass "$name"; return 0; fi
  bad "$name" "$why
$( (jq -c . <<<"$body" 2>/dev/null || printf '%s' "$body") | cut -c1-700)"
  return 1
}

# check NAME COMMAND...: PASS when the command succeeds
check() {
  local name=$1 out
  shift
  if out=$("$@" 2>&1); then pass "$name"; return 0; fi
  bad "$name" "$out"
  return 1
}

# poll SECONDS COMMAND...: retries every 2 s until the command succeeds
poll() {
  local limit=$1 start=$SECONDS
  shift
  until "$@" >/dev/null 2>&1; do
    [ $((SECONDS - start)) -ge "$limit" ] && return 1
    sleep 2
  done
}

# ---------------------------------------------------------------------------------------------
# infrastructure of local/compose.yaml

sql() { "${compose[@]}" exec -T postgres psql -U app -d "$1" -tAc "$2" 2>&1; }
awsl() { "${compose[@]}" exec -T localstack awslocal "$@" 2>&1; }
kafka() { local tool=$1; shift; "${compose[@]}" exec -T kafka "/opt/kafka/bin/$tool" --bootstrap-server localhost:9092 "$@" 2>/dev/null; }

# messages written to a topic (sum of the end offsets of its partitions)
topic_size() { kafka kafka-get-offsets.sh --topic "$1" | awk -F: '{s += $3} END {print s + 0}'; }

# consumers of the group are connected and have read everything on the topic
caught_up() {
  local out
  out=$(kafka kafka-consumer-groups.sh --describe --group "$1") || return 1
  awk -v t="${2:-payments}" '
    $2 == t { rows++; lag = ($6 == "-") ? $5 : $6; total += lag; if ($7 != "-") members++ }
    END { if (rows == 0 || members == 0 || total > 0) exit 1 }' <<<"$out"
}
lag_report() {
  kafka kafka-consumer-groups.sh --describe --group "$1" | awk 'NF && $1 != ""' | head -n 6
}

# objects archived today under audit/. The CLI paginates list-objects-v2 and merges the pages,
# which drops KeyCount (it printed None): count the merged Contents instead
audit_objects() {
  local n
  # shellcheck disable=SC2016 # JMESPath literal, not a shell expansion
  n=$(awsl s3api list-objects-v2 --bucket local-bucket --prefix "audit/$(date -u +%Y/%m/%d)/" \
    --query 'length(Contents || `[]`)' --output text | tr -dc '0-9')
  echo "${n:-0}"
}

# ---------------------------------------------------------------------------------------------
# tokens and data

token_user() {
  curl -s "$KC_URL/realms/axiz/protocol/openid-connect/token" -d grant_type=password \
    -d client_id=payments-web -d username="$1" -d password="local-only-$1" | jq -r '.access_token // empty'
}
token_client() {
  curl -s "$KC_URL/realms/axiz/protocol/openid-connect/token" -d grant_type=client_credentials \
    -d client_id="$1" -d client_secret="$2" | jq -r '.access_token // empty'
}
# claim TOKEN NAME: a claim of the JWT payload
claim() {
  local p
  p=$(cut -d. -f2 <<<"$1" | tr '_-' '/+')
  while [ $((${#p} % 4)) -ne 0 ]; do p+="="; done
  base64 -d <<<"$p" 2>/dev/null | jq -r --arg n "$2" '.[$n] // empty'
}
tokens_at=-1000
tokens() {
  # tokens last 15 minutes: renewed for every group
  [ $((SECONDS - tokens_at)) -lt 300 ] && return 0
  ANA=$(token_user ana)
  LUIS=$(token_user luis)
  M2M=$(token_client payment-process local-only-payment-process)
  OTHER=$(token_client local-client local-only)
  if [ -z "$ANA" ] || [ -z "$LUIS" ] || [ -z "$M2M" ] || [ -z "$OTHER" ]; then
    printf '\033[31mFAILED: no tokens from Keycloak (%s)\033[0m\n' "$KC_URL" >&2
    exit 1
  fi
  # payment-process is multi-tenant: tokens must carry the claim tenant (realm of this demo)
  if [ "$(claim "$M2M" tenant)" != demo ]; then
    printf '\033[31mFAILED: the tokens have no claim "tenant": Keycloak runs an older realm.\033[0m\n' >&2
    echo "  scripts/demo.sh down && scripts/demo.sh up   (imports local/keycloak/axiz-realm.json again)" >&2
    exit 1
  fi
  tokens_at=$SECONDS
}

# beneficiary CUSTOMER TOKEN ALIAS HOLDER CCI CURRENCY: id of the active beneficiary with that
# CCI, registering it when missing
beneficiary() {
  local c=$1 t=$2
  call POST "$CRUD/v1/customers/$c/beneficiaries" -H "Authorization: Bearer $t" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" \
    -d "{\"alias\":\"$3\",\"holderName\":\"$4\",\"cci\":\"$5\",\"currency\":\"$6\"}"
  if [ "$code" = 201 ]; then jq -r .id <<<"$body"; return; fi
  call GET "$CRUD/v1/customers/$c/beneficiaries?size=100" -H "Authorization: Bearer $t"
  jq -r --arg cci "$5" '.items[] | select(.cci == $cci) | .id' <<<"$body" | head -1
}

need_beneficiaries() {
  [ -n "${BEN:-}" ] && return
  BEN=$(beneficiary C-1001 "$ANA" Landlord "Ana Torres" "$CCI_LANDLORD" PEN)
  CLOSED=$(beneficiary C-1001 "$ANA" "Cuenta cerrada" "Pedro Ruiz" "$CCI_CLOSED" PEN)
  USDBEN=$(beneficiary C-1001 "$ANA" "Dolares" "Ana Torres" "$CCI_USD" USD)
  LUISBEN=$(beneficiary C-1002 "$LUIS" Hermano "Luis Demo" "$CCI_LUIS" PEN)
  if [ -z "$BEN" ] || [ -z "$CLOSED" ] || [ -z "$USDBEN" ] || [ -z "$LUISBEN" ]; then
    bad "beneficiaries for the scenarios" "Landlord=$BEN closed=$CLOSED usd=$USDBEN luis=$LUISBEN"
    return 1
  fi
}

# a completed payment of ana (and a rejected one), created by the payment and saga groups when
# they did not run before
need_payment() {
  [ -n "${PAY:-}" ] && return
  local saved=("${involved[@]}") g=$group
  note "(first, a payment: group payment)"
  group=payment; g_payment; group=$g
  involved=("${saved[@]}")
}
need_rejected() {
  [ -n "${NOFUNDS:-}" ] && return
  local saved=("${involved[@]}") g=$group
  note "(first, the rejections: group saga)"
  group=saga; g_saga; group=$g
  involved=("${saved[@]}")
}

# an amount not used yet in this run: the same amount to the same beneficiary on the same day is
# rejected as a probable duplicate
amount() {
  local a
  while :; do
    a=$(printf '%d.%02d' $((10 + RANDOM % 90)) $((RANDOM % 100)))
    [[ "$used_amounts" == *" $a "* ]] || break
  done
  used_amounts+="$a "
  echo "$a"
}

# pay TOKEN BENEFICIARY ACCOUNT AMOUNT CURRENCY [IDEMPOTENCY-KEY]
pay() {
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $1" "${JSON[@]}" -H "Idempotency-Key: ${6:-$(key)}" \
    -d "{\"beneficiaryId\":\"$2\",\"debtorAccount\":\"$3\",\"amount\":$4,\"currency\":\"$5\",\"description\":\"scenario\"}"
  if [ "$code" = 201 ]; then
    paid=$(jq -r .paymentId <<<"$body")
    run_ids+=("$paid")
  else
    paid=""
  fi
}

order_status() { curl -s "$EXP/v1/payments/$1" -H "Authorization: Bearer $2" | jq -r '.status // empty'; }
is_final() { local s; s=$(order_status "$1" "$2"); [ "$s" = COMPLETED ] || [ "$s" = REJECTED ]; }

# final_status ID TOKEN: waits (up to 120 s) for COMPLETED or REJECTED
final_status() {
  poll "${SCENARIOS_SAGA_WAIT:-120}" is_final "$1" "$2"
  order_status "$1" "$2"
}

process_view() { call GET "$PROC/v1/payment-processes/$1" -H "Authorization: Bearer $M2M"; }
process_status() { curl -s "$PROC/v1/payment-processes/$1" -H "Authorization: Bearer $M2M" | jq -r '.status // empty'; }
history_status() { curl -s "$HIST/v1/payments/$1" -H "Authorization: Bearer $ANA" | jq -r '.status // empty'; }
process_is() { [ "$(process_status "$1")" = "$2" ]; }
# the saga stopped running: VERIFYING or finished
process_stopped() { [[ " VERIFYING COMPLETED REJECTED NEEDS_ATTENTION " == *" $(process_status "$1") "* ]]; }
process_final() { [[ " COMPLETED REJECTED NEEDS_ATTENTION " == *" $(process_status "$1") "* ]]; }
history_is() { [ "$(history_status "$1")" = "$2" ]; }
reserved() { sql payments_business "SELECT COALESCE(SUM(reserved),0)::numeric(18,2) FROM payment_daily_usage WHERE customer_id='C-1001' AND currency='PEN'"; }

# WireMock journal: reversals requested so far
reversals() {
  curl -s "$WM/__admin/requests?limit=1000" \
    | jq '[.requests[] | select(.request.method == "POST" and (.request.url | test("/reversal$")))] | length'
}
# id of the movement the core returned for the debit with reference $1 (the operation number)
movement_of() {
  curl -s "$WM/__admin/requests?limit=1000" | jq -r --arg op "$1" '
    [.requests[] | select(.request.url | test("/debits$"))
      | select((.request.body | fromjson? | .ref) == $op)
      | .response.body | fromjson? | .mvId // empty] | first // empty'
}

# ---------------------------------------------------------------------------------------------
# groups

g_beneficiaries() {
  involved=(beneficiaries-crud payments-experience)
  local id
  need_beneficiaries || return
  pass "beneficiaries of ana (Landlord, closed account, USD) and luis registered or reused"

  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d '{"alias":"Mal","holderName":"X","cci":"12345678901234567890","currency":"PEN"}'
  expect "CCI with wrong check digits is rejected (422)" 422 '.code == "PAYMENTS-BENEFICIARIESCRUD-0001"'

  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d "{\"alias\":\"Otra vez\",\"holderName\":\"Ana Torres\",\"cci\":\"$CCI_LANDLORD\",\"currency\":\"PEN\"}"
  expect "one active beneficiary per account (409)" 409 '.code == "PAYMENTS-BENEFICIARIESCRUD-0002"'

  # lifecycle on a beneficiary of its own (removed again at the end)
  id=$(beneficiary C-1001 "$ANA" Temporal "Rosa Diaz" "$CCI_TEMP" PEN)
  if [ -z "$id" ]; then bad "register a temporary beneficiary" "HTTP $code $body"; return; fi
  call PUT "$CRUD/v1/customers/C-1001/beneficiaries/$id" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -d '{"alias":"Temporal renombrado"}'
  expect "rename (PUT)" 200 '.alias == "Temporal renombrado"'
  call GET "$CRUD/v1/customers/C-1001/beneficiaries/$id" -H "Authorization: Bearer $ANA"
  expect "read after rename shows the new alias (cache evicted)" 200 '.alias == "Temporal renombrado"'
  call DELETE "$CRUD/v1/customers/C-1001/beneficiaries/$id" -H "Authorization: Bearer $ANA"
  expect "remove (soft delete, 204)" 204
  call GET "$CRUD/v1/customers/C-1001/beneficiaries/$id" -H "Authorization: Bearer $ANA"
  expect "a removed beneficiary is not found (404)" 404
  local key1 again
  key1=$(key)
  again="{\"alias\":\"Temporal\",\"holderName\":\"Rosa Diaz\",\"cci\":\"$CCI_TEMP\",\"currency\":\"PEN\"}"
  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $key1" -d "$again"
  expect "the account of a removed beneficiary can be registered again (201)" 201
  id=$(jq -r '.id // empty' <<<"$body")
  # the client lost the answer and retries: idempotency on DynamoDB returns the same beneficiary
  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $key1" -d "$again"
  expect "a retried registration (same Idempotency-Key) returns the same beneficiary" 201 ".id == \"$id\""
  grep -qi '^idempotent-replayed: true' <<<"$headers" && pass "  ... replayed from DynamoDB (Idempotent-Replayed: true)" \
    || bad "  ... replayed from DynamoDB (Idempotent-Replayed: true)" "$headers"
  [ -n "$id" ] && curl -s -o /dev/null -X DELETE "$CRUD/v1/customers/C-1001/beneficiaries/$id" -H "Authorization: Bearer $ANA"

  call GET "$CRUD/v1/customers/C-1001/beneficiaries?size=1" -H "Authorization: Bearer $ANA"
  expect "cursor pagination: first page of 1 with nextCursor" 200 '.items | length == 1' '.nextCursor | type == "string"'
  local first cursor
  first=$(jq -r '.items[0].id' <<<"$body"); cursor=$(jq -r '.nextCursor // empty' <<<"$body")
  call GET "$CRUD/v1/customers/C-1001/beneficiaries?size=1&cursor=$cursor" -H "Authorization: Bearer $ANA"
  expect "cursor pagination: the next page has another beneficiary" 200 ".items[0].id != \"$first\""
  call GET "$CRUD/v1/customers/C-1001/beneficiaries?cursor=not-a-cursor" -H "Authorization: Bearer $ANA"
  expect "a malformed cursor is rejected (400)" 400 '.code == "PAYMENTS-BENEFICIARIESCRUD-0004"'

  call GET "$EXP/v1/beneficiaries?size=20" -H "Authorization: Bearer $ANA"
  expect "channel list masks accounts and names" 200 '.items | length > 0' \
    'all(.items[]; (.creditorCci | test("^\\*+[0-9]{4}$")) and (.holderName | contains("*")))'
}

g_payment() {
  involved=(payments-experience payments-business payment-process core-integration payments-history)
  need_beneficiaries || return
  PAY_KEY=$(key)
  PAY_AMOUNT=$(amount)
  pay "$ANA" "$BEN" "$ACCOUNT" "$PAY_AMOUNT" PEN "$PAY_KEY"
  expect "pay through the channel (201, RECEIVED, masked CCI)" 201 '.status == "RECEIVED"' \
    '.operationNumber | test("^OP[0-9]{8}-[0-9]+$")' '.creditorCci == "****************9053"' \
    '.beneficiaryAlias == "Landlord"' || return
  PAY=$paid
  PAY_OP=$(jq -r .operationNumber <<<"$body")
  note "payment $PAY ($PAY_OP)"
  local s
  s=$(final_status "$PAY" "$ANA")
  [ "$s" = COMPLETED ] && pass "the saga completes the order (COMPLETED)" \
    || { bad "the saga completes the order (COMPLETED)" "status: ${s:-none}"; return; }

  process_view "$PAY"
  expect "process view for payments:process (COMPLETED)" 200 '.status == "COMPLETED"'
  call GET "$PROC/v1/payment-processes/$PAY" -H "Authorization: Bearer $ANA"
  expect "a customer cannot read the process (403)" 403

  if poll 60 history_is "$PAY" COMPLETED; then
    call GET "$HIST/v1/payments/$PAY" -H "Authorization: Bearer $ANA"
    expect "history projects the payment (Kafka -> R2DBC)" 200 '.status == "COMPLETED"' \
      ".operationNumber == \"$PAY_OP\"" '.customerId == "C-1001"' '.creditorCci | test("^\\*+9053$")'
  else
    bad "history projects the payment (Kafka -> R2DBC)" "history status: $(history_status "$PAY")"
  fi
  call GET "$HIST/v1/customers/C-1001/payments?page=0&size=20" -H "Authorization: Bearer $ANA"
  # shellcheck disable=SC2016
  expect "history of the customer lists it, latest change first" 200 \
    "any(.items[]; .paymentId == \"$PAY\")" '[.items[].lastChangeAt] as $t | $t == ($t | sort | reverse)'

  call GET "$BUS/v1/payment-orders/$PAY" -H "Authorization: Bearer $M2M"
  expect "payments-business order readable by payments:process" 200 '.status == "COMPLETED"'
}

g_idempotency() {
  involved=(payments-experience)
  need_payment
  [ -n "${PAY:-}" ] || return
  pay "$ANA" "$BEN" "$ACCOUNT" "$PAY_AMOUNT" PEN "$PAY_KEY"
  expect "the same request with the same key returns the same payment (201)" 201 ".paymentId == \"$PAY\""
  grep -qi '^idempotent-replayed: true' <<<"$headers" && pass "  ... replayed (Idempotent-Replayed: true), no second order" \
    || bad "  ... replayed (Idempotent-Replayed: true), no second order" "$headers"
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $PAY_KEY" \
    -d "{\"beneficiaryId\":\"$BEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":999.00,\"currency\":\"PEN\"}"
  expect "the same key with another body is rejected (422)" 422 '.code == "AXZ-IDEM-0422"'
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -d "{\"beneficiaryId\":\"$BEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":$(amount),\"currency\":\"PEN\"}"
  expect "a payment without Idempotency-Key is rejected (400)" 400 '.code == "AXZ-IDEM-0400" or .code == "AXZ-CORE-0002"'
}

g_rules() {
  involved=(payments-experience payments-business)
  need_payment
  need_beneficiaries || return
  if [ -n "${PAY:-}" ]; then
    pay "$ANA" "$BEN" "$ACCOUNT" "$PAY_AMOUNT" PEN
    expect "same amount to the same beneficiary today: probable duplicate (409)" 409 \
      '.code == "PAYMENTS-PAYMENTSEXPERIENCE-0004"'
  fi
  pay "$ANA" "$BEN" "$ACCOUNT" 30000.01 PEN
  expect "above the daily limit of 30 000 PEN (422)" 422 '.code == "PAYMENTS-PAYMENTSEXPERIENCE-0003"'
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -H "Accept-Language: es-PE" -d "{\"beneficiaryId\":\"$USDBEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"USD\"}"
  expect "USD payments are off (feature flag payments-usd), message in Spanish" 422 \
    '.code == "PAYMENTS-PAYMENTSEXPERIENCE-0002"' '.detail | test("no están disponibles")'
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -H "Accept-Language: en" -d "{\"beneficiaryId\":\"$USDBEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"USD\"}"
  expect "  ... and in English with Accept-Language: en (i18n)" 422 '.detail | test("are not available")'
  pay "$ANA" "$USDBEN" "$ACCOUNT" 10 PEN
  expect "PEN to a USD beneficiary: currency mismatch (422)" 422 '.code == "PAYMENTS-PAYMENTSEXPERIENCE-0001"'
}

# rejected NAME TOKEN BENEFICIARY ACCOUNT FAILED-STEP: pays, waits REJECTED and checks the process
rejected() {
  local name=$1 s
  pay "$2" "$3" "$4" "$(amount)" PEN
  if [ "$code" != 201 ]; then bad "$name: payment accepted (201)" "HTTP $code $body"; return 1; fi
  s=$(final_status "$paid" "$2")
  if [ "$s" != REJECTED ]; then bad "$name: order REJECTED" "status: ${s:-none}"; return 1; fi
  process_view "$paid"
  expect "$name: REJECTED, failed step $5, compensated" 200 '.status == "REJECTED"' ".failedStep == \"$5\""
}

g_saga() {
  involved=(payment-process core-integration payments-business)
  need_beneficiaries || return
  local r0 r1 before after op mv
  r0=$(reserved)
  rejected "no funds (CB-51)" "$ANA" "$BEN" "$NO_FUNDS" debit && NOFUNDS=$paid
  r1=$(reserved)
  [ "$r0" = "$r1" ] && pass "  ... the rejection gives the amount back to the daily limit ($r1 reserved)" \
    || bad "  ... the rejection gives the amount back to the daily limit" "reserved before $r0, after $r1"
  if [ -n "${NOFUNDS:-}" ]; then
    involved=(payments-history)
    if poll 60 history_is "$NOFUNDS" REJECTED; then
      call GET "$HIST/v1/payments/$NOFUNDS" -H "Authorization: Bearer $ANA"
      expect "  ... history shows REJECTED with its reason" 200 '.status == "REJECTED"' '(.reason // "") | length > 0'
    else
      bad "  ... history shows REJECTED with its reason" "history status: $(history_status "$NOFUNDS")"
    fi
    involved=(payment-process core-integration payments-business)
  fi

  rejected "blocked account (CB-62)" "$ANA" "$BEN" "$BLOCKED" debit

  before=$(reversals)
  if rejected "closed destination account (NET-R02)" "$ANA" "$CLOSED" "$ACCOUNT" transfer; then
    op=$(curl -s "$EXP/v1/payments/$paid" -H "Authorization: Bearer $ANA" | jq -r .operationNumber)
    mv=$(movement_of "$op")
    after=$(reversals)
    if [ "$after" -gt "$before" ] && { [ -z "$mv" ] || curl -s "$WM/__admin/requests?limit=1000" \
      | jq -e --arg mv "$mv" 'any(.requests[]; .request.url | contains("/movements/" + $mv + "/reversal"))' >/dev/null; }; then
      pass "  ... the debit ${mv:-} was reversed in the core (compensation)"
    else
      bad "  ... the debit was reversed in the core (compensation)" "reversals before $before, after $after, movement ${mv:-?}"
    fi
  fi

  rejected "luis pays from ana's account (CB-17)" "$LUIS" "$LUISBEN" "$ACCOUNT" debit
}

g_security() {
  involved=(payments-experience payments-business payments-history beneficiaries-crud core-integration)
  need_payment
  [ -n "${PAY:-}" ] || return
  call GET "$EXP/v1/payments/$PAY"
  expect "without a token (401)" 401 '.code == "AXZ-SEC-0401"'
  call GET "$EXP/v1/payments/$PAY" -H "Authorization: Bearer not-a-token"
  expect "with an invalid token (401)" 401
  call GET "$EXP/v1/payments/$PAY" -H "Authorization: Bearer $LUIS"
  expect "luis reads ana's payment in the channel: not found (404)" 404
  call GET "$BUS/v1/payment-orders/$PAY" -H "Authorization: Bearer $LUIS"
  expect "  ... in payments-business: not found (404)" 404
  call GET "$HIST/v1/payments/$PAY" -H "Authorization: Bearer $LUIS"
  expect "  ... in payments-history: not found (404)" 404
  call GET "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $LUIS"
  expect "luis lists ana's beneficiaries (403)" 403 '.code == "AXZ-SEC-0403"'
  call GET "$HIST/v1/customers/C-1001/payments" -H "Authorization: Bearer $LUIS"
  expect "luis lists ana's history (403)" 403 '.code == "AXZ-SEC-0403"'
  call POST "$BUS/v1/payment-orders/$PAY/status" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d '{"status":"COMPLETED"}'
  expect "ana changes the status of her own order (403)" 403
  call POST "$BUS/v1/payment-orders/$PAY/status" -H "Authorization: Bearer $OTHER" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d '{"status":"COMPLETED"}'
  expect "an application without payments:process changes a status (403)" 403
  call POST "$CORE/v1/debits" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"accountId\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"PEN\",\"holderId\":\"C-1001\",\"reference\":\"X\"}"
  expect "a customer debits an account directly (403)" 403
  call GET "$CORE/actuator/prometheus"
  expect "actuator beyond health and info needs a token (401)" 401
  call GET "$CORE/actuator/prometheus" -H "Authorization: Bearer $ANA"
  expect "  ... and the actuator scope (403)" 403
  call GET "$CORE/actuator/health"
  expect "health is public" 200 '.status == "UP"'
}

g_integration() {
  involved=(core-integration)
  call POST "$CORE/v1/debits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"accountId\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"PEN\",\"holderId\":\"C-1002\",\"reference\":\"X\"}"
  expect "debit of an account of another holder (422, checked by the core)" 422 '.code == "PAYMENTS-COREINTEGRATION-0004"'
  call POST "$CORE/v1/credits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"accountId\":\"$ACCOUNT\",\"amount\":15.50,\"currency\":\"PEN\",\"holderId\":\"C-1001\",\"reference\":\"SCN-$RANDOM\"}"
  expect "credit through the SOAP service of the core (WS-Security)" 201 '.movementId | length > 0'
  call POST "$CORE/v1/debits/MV-404/reversal" -H "Authorization: Bearer $M2M" -H "Idempotency-Key: $(key)"
  expect "reversal of an unknown movement (404)" 404
  call POST "$CORE/v1/transfers" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"creditorCci\":\"$CCI_CLOSED\",\"amount\":10,\"currency\":\"PEN\",\"reference\":\"SCN-$RANDOM\"}"
  expect "transfer to a closed account: rejected by the network (422)" 422 '.code == "PAYMENTS-COREINTEGRATION-0003"'
}

g_events() {
  involved=(payments-business payment-process payments-history notifications-processor)
  local ids g n bad_sagas
  need_payment
  sleep 3
  n=$(sql payments_business "SELECT count(*) FROM axiz_outbox WHERE status <> 'PUBLISHED'")
  [ "$n" = 0 ] && pass "outbox of payments-business: every event PUBLISHED" \
    || bad "outbox of payments-business: every event PUBLISHED" "not published: $n
$(sql payments_business "SELECT event_type, status, attempts, last_error FROM axiz_outbox WHERE status <> 'PUBLISHED' LIMIT 5")"
  if [ -n "${PAY:-}" ]; then
    n=$(sql payments_business "SELECT count(*) FROM payment_order WHERE id = '$PAY' AND debtor_account LIKE 'axz1:%' AND creditor_cci LIKE 'axz1:%'")
    [ "$n" = 1 ] && pass "accounts stored encrypted (axz1:...)" \
      || bad "accounts stored encrypted (axz1:...)" "$(sql payments_business "SELECT left(debtor_account,12), left(creditor_cci,12) FROM payment_order WHERE id = '$PAY'")"
  fi
  if [ ${#run_ids[@]} -gt 0 ]; then
    ids=$(printf "'%s'," "${run_ids[@]}"); ids=${ids%,}
    bad_sagas=$(sql payment_process "SELECT saga_id || ' ' || status FROM axiz_saga WHERE saga_id IN ($ids) AND status NOT IN ('COMPLETED','COMPENSATED')")
    [ -z "$bad_sagas" ] && pass "sagas of this run ended COMPLETED or COMPENSATED (${#run_ids[@]})" \
      || bad "sagas of this run ended COMPLETED or COMPENSATED" "$bad_sagas"
  fi
  for g in payment-process payments-history notifications-processor; do
    if poll 30 caught_up "$g"; then pass "Kafka consumer group $g connected and caught up on payments"
    else bad "Kafka consumer group $g connected and caught up on payments" "$(lag_report "$g")"; fi
  done
  n=$(topic_size payments.dlq)
  [ "$n" = "$DLQ0" ] && pass "nothing went to payments.dlq during the run ($n in total)" \
    || bad "nothing went to payments.dlq during the run" "before $DLQ0, now $n"
  local msg
  msg=$(kafka kafka-console-consumer.sh --topic payments --from-beginning --max-messages 20 \
    --property print.headers=true --timeout-ms 15000)
  grep -q 'ce_type:pe.axiz.payments.payment-order' <<<"$msg" && grep -q 'ce_id:' <<<"$msg" \
    && pass "events on Kafka are CloudEvents (ce_type, ce_id headers)" \
    || bad "events on Kafka are CloudEvents (ce_type, ce_id headers)" "$(cut -c1-400 <<<"$msg")"
}

g_notifications() {
  involved=(notifications-processor)
  local q="http://localhost:4566/000000000000/local-customer-notifications-probe" seen="" out start
  if ! awsl sqs get-queue-url --queue-name local-customer-notifications-probe >/dev/null; then
    bad "probe queue of the notification topic" "missing: LocalStack was initialized by an older demo. scripts/demo.sh down && scripts/demo.sh up"
    return
  fi
  need_payment
  need_rejected
  # drains the probe queue (it also holds the notifications of earlier runs) until both appear
  local entries
  start=$SECONDS
  while [ $((SECONDS - start)) -lt 90 ]; do
    out=$(awsl sqs receive-message --queue-url "$q" --max-number-of-messages 10 --wait-time-seconds 2)
    entries=$(jq -c '[.Messages[]? | {Id: .MessageId, ReceiptHandle}]' <<<"$out" 2>/dev/null)
    [ -n "$entries" ] && [ "$entries" != "[]" ] \
      && awsl sqs delete-message-batch --queue-url "$q" --entries "$entries" >/dev/null
    seen+=$(jq -r '.Messages[]?.Body' <<<"$out" 2>/dev/null)
    { [ -z "${PAY:-}" ] || grep -q "$PAY" <<<"$seen"; } && { [ -z "${NOFUNDS:-}" ] || grep -q "$NOFUNDS" <<<"$seen"; } && break
  done
  if [ -n "${PAY:-}" ]; then
    grep -q "$PAY" <<<"$seen" && grep -q 'payment-completed' <<<"$seen" \
      && pass "completed payment: notification request on SNS (payment-completed, EMAIL to ana)" \
      || bad "completed payment: notification request on SNS" "no message with $PAY in 90 s"
  fi
  if [ -n "${NOFUNDS:-}" ]; then
    grep -q "$NOFUNDS" <<<"$seen" && grep -q 'payment-rejected' <<<"$seen" \
      && pass "rejected payment: notification request on SNS (payment-rejected)" \
      || bad "rejected payment: notification request on SNS" "no message with $NOFUNDS in 90 s"
  fi
}

audit_grew() { [ "$(audit_objects)" -gt "${AUDIT0:-0}" ]; }
audit_topic_grew() { [ "$(topic_size audit)" -gt "${AUDIT_TOPIC0:-0}" ]; }
g_audit() {
  involved=(core-integration notifications-processor payments-business)
  local n
  need_payment
  if poll 90 audit_grew; then
    pass "audit of core-integration archived on S3 by notifications-processor (SQS -> S3): $(audit_objects) objects today"
  else
    bad "audit of core-integration archived on S3 (SQS -> S3)" "objects under audit/$(date -u +%Y/%m/%d)/: before $AUDIT0, now $(audit_objects)"
  fi
  awsl s3 ls "s3://local-bucket/audit/$(date -u +%Y/%m/%d)/_manifest.json" | grep -q _manifest \
    && pass "  ... with the daily manifest (counts per service, DynamoDB lock)" \
    || bad "  ... with the daily manifest" "no audit/$(date -u +%Y/%m/%d)/_manifest.json"
  poll 30 audit_topic_grew && pass "audit of payments-business on the Kafka topic audit (outbox)" \
    || bad "audit of payments-business on the Kafka topic audit" "messages before $AUDIT_TOPIC0, now $(topic_size audit)"
  n=$(awsl sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/axiz-demo-audit-dlq \
    --attribute-names ApproximateNumberOfMessages --query Attributes.ApproximateNumberOfMessages --output text | tr -dc '0-9')
  [ "${n:-0}" = 0 ] && pass "audit DLQ empty" || bad "audit DLQ empty" "$n messages in axiz-demo-audit-dlq"
}

grafana() {
  local out
  out=$(curl -s -w '\n%{http_code}' "$GRAFANA$1")
  # anonymous access first (otel-lgtm), then the default admin account
  if [ "${out##*$'\n'}" != 200 ]; then out=$(curl -s -w '\n%{http_code}' -u admin:admin "$GRAFANA$1"); fi
  printf '%s' "${out%$'\n'*}"
}
promql() {
  grafana "/api/datasources/proxy/uid/$PROM_UID/api/v1/query?query=$(jq -rn --arg q "$1" '$q|@uri')" \
    | jq -r '[.data.result[]?.value[1] | tonumber] | add // 0'
}
prom_positive() { awk -v v="$(promql "$1")" 'BEGIN { exit !(v > 0) }'; }
traceql() {
  local now; now=$(date +%s)
  grafana "/api/datasources/proxy/uid/$TEMPO_UID/api/search?q=$(jq -rn --arg q "$1" '$q|@uri')&start=$((now - 3600))&end=$now&limit=5" \
    | jq -e '(.traces // []) | length > 0'
}
# log_trace SERVICE REGEX: trace ID of the last line of .run/SERVICE.log matching REGEX (the log
# pattern prints [traceId-spanId] of the current span; empty when the line ran outside a trace)
log_trace() {
  grep -E "$2" "$root/.run/$1.log" 2>/dev/null | grep -oE '\[[0-9a-f]{32}-[0-9a-f]{16}\]' | tail -1 | cut -c2-33
}
# trace_services TRACE-ID: service names of the spans Tempo holds for that trace
trace_services() {
  grafana "/api/datasources/proxy/uid/$TEMPO_UID/api/traces/$1" | jq -r '
    [ (.batches // .resourceSpans // [])[] | .resource.attributes[]?
      | select(.key == "service.name") | .value.stringValue ] | unique | join(" ")' 2>/dev/null
}
# trace_has TRACE-ID SERVICE...: the trace holds spans of every service
trace_has() {
  local t=$1 have s; shift
  have=" $(trace_services "$t") "
  for s in "$@"; do [[ "$have" == *" $s "* ]] || return 1; done
}
g_observability() {
  involved=()
  local ds
  ds=$(grafana /api/datasources)
  PROM_UID=$(jq -r '.[]? | select(.type == "prometheus") | .uid' <<<"$ds" | head -1)
  TEMPO_UID=$(jq -r '.[]? | select(.type == "tempo") | .uid' <<<"$ds" | head -1)
  if [ -z "$PROM_UID" ] || [ -z "$TEMPO_UID" ]; then
    bad "Grafana datasources (Prometheus, Tempo)" "$(cut -c1-300 <<<"$ds")"
    return
  fi
  need_payment
  local q='sum({__name__=~"axiz_events(_total)?", event="payment.order.created"})'
  poll 120 prom_positive "$q" && pass "business metrics in Prometheus (axiz events: payment.order.created)" \
    || bad "business metrics in Prometheus" "$q = $(promql "$q")"
  q='sum({__name__=~"http_server_request.*_count", job=~".*payments-experience.*"})'
  poll 60 prom_positive "$q" && pass "HTTP server metrics of payments-experience (OTLP)" \
    || bad "HTTP server metrics of payments-experience (OTLP)" "$q = $(promql "$q")"
  # the trace IDs come from the logs ([traceId-spanId] on every line written inside a span), so a
  # failure says where the trace broke: no ID in the caller, another ID in the callee, or spans
  # missing in Tempo
  local t
  t=$(log_trace payment-process 'client=core-integration ')
  if [ -z "$t" ]; then
    bad "payment-process calls core-integration inside a trace" \
      "no 'client=core-integration' line with a trace ID in .run/payment-process.log"
  elif ! grep -q "\[$t-" "$root/.run/core-integration.log"; then
    bad "core-integration continues the trace of payment-process (HTTP traceparent)" \
      "trace $t of payment-process is not in .run/core-integration.log; its last request line:
$(grep -E 'axiz.http .*path=/v1/' "$root/.run/core-integration.log" | tail -1 | cut -c1-400)"
  else
    pass "core-integration continues the trace of payment-process (trace $t in both logs)"
    poll 60 trace_has "$t" payment-process core-integration \
      && pass "  ... and Tempo holds that trace with both services" \
      || bad "  ... and Tempo holds that trace with both services" "services in trace $t: $(trace_services "$t")"
  fi
  # the request that registers the payment and its saga are one trace: the outbox keeps the
  # context of the write, the relay continues it and Kafka carries it to the consumers
  local want=(payments-experience payments-business payment-process core-integration)
  t=$(log_trace payments-business 'path=/v1/payment-orders status=201')
  if [ -z "$t" ]; then
    bad "one trace from the channel request to the saga" \
      "no 'path=/v1/payment-orders status=201' line with a trace ID in .run/payments-business.log"
  else
    poll 90 trace_has "$t" "${want[@]}" \
      && pass "one trace from the channel request to the saga: ${want[*]}" \
      || bad "one trace from the channel request to the saga: ${want[*]}" \
        "trace $t of the last order created has: $(trace_services "$t")"
  fi
  q='{name="outbox write payments"} && {name="outbox dispatch payments"}'
  poll 30 traceql "$q" && pass "  ... with the outbox write and its dispatch by the relay" \
    || bad "  ... with the outbox write and its dispatch by the relay" "no trace for $q"
}

settle() {
  local jar=$1 key=$2 log=$3
  (
    cd "$root/services/settlement-batch" || exit 1
    export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
    # shellcheck disable=SC2086
    timeout 300 java ${AXIZ_JAVA_OPTS:--XX:TieredStopAtLevel=1} -jar "$jar" \
      --spring.profiles.active=local,platform --settlement.input-key="$key" > "$log" 2>&1
  )
}
# a settlement file of its own for this run: the sample with end-to-end ids nobody loaded before
# (same widths, amounts and control totals). Each run checks a fresh load, its rerun and its
# summary, whatever state earlier runs left in Aurora or S3 (LocalStack keeps no data between
# restarts, PostgreSQL does)
settlement_file() {
  local key=$1 run=$2
  sed "s/^DOP20261011-/DSC${run}-/" "$root/local/localstack/NET-SETTLE-SAMPLE.txt" \
    | awsl s3 cp - "s3://local-bucket/$key" >/dev/null
}
g_settlement() {
  involved=(settlement-batch)
  local jar log="$root/.run/settlement-batch.log" rc n run key name
  jar=$(find "$root/services/settlement-batch/target" -maxdepth 1 -name 'settlement-batch-*.jar' \
    ! -name '*-sources.jar' ! -name '*-plain.jar' 2>/dev/null | head -1)
  if [ -z "$jar" ]; then
    bad "settlement-batch jar" "missing: scripts/demo.sh up builds it (or ./mvnw clean package -DskipTests -pl services/settlement-batch -am)"
    return
  fi
  # 8 digits replace the 8 characters of "20261011" in the end-to-end id
  run=$(date +%s | tail -c 9)
  name="NET-SETTLE-SCN${run}.txt"
  key="in/settlement/$name"
  settlement_file "$key" "$run"
  settle "$jar" "$key" "$log"; rc=$?
  if [ "$rc" = 0 ] && grep -q 'Settlement job for .* ended COMPLETED' "$log"; then
    pass "the job loads a new file, $key (exit 0, COMPLETED)"
  else
    bad "the job loads a new file, $key (exit 0, COMPLETED)" "exit $rc; .run/settlement-batch.log:
$(grep -E ' (WARN|ERROR) |Exception|Settlement' "$log" | tail -n 8 | cut -c1-300)"
  fi
  settle "$jar" "$key" "$log.2"; rc=$?
  [ "$rc" = 0 ] && grep -q 'was already loaded' "$log.2" && pass "running it again loads nothing twice (already loaded, exit 0)" \
    || bad "running it again loads nothing twice" "exit $rc; $(grep -E 'Settlement|ERROR' "$log.2" | tail -n 4 | cut -c1-300)"
  n=$(sql settlement_batch "SELECT count(*) FROM settlement_line WHERE file_key = '$key'")
  [ "$n" = 3 ] && pass "3 settlement lines in Aurora, control totals matched" || bad "3 settlement lines in Aurora" "$n lines"
  awsl s3 ls "s3://local-bucket/out/settlement/$name.summary.json" | grep -q summary \
    && pass "summary on S3 (out/settlement/$name.summary.json)" \
    || bad "summary on S3" "no out/settlement/$name.summary.json"
  # the bucket loses the summary (as when LocalStack restarts): a rerun writes it again from Aurora
  awsl s3 rm "s3://local-bucket/out/settlement/$name.summary.json" >/dev/null
  settle "$jar" "$key" "$log.3"; rc=$?
  [ "$rc" = 0 ] && grep -q 'writing it again' "$log.3" \
    && awsl s3 ls "s3://local-bucket/out/settlement/$name.summary.json" | grep -q summary \
    && pass "a rerun writes a lost summary again (exit 0, rows unchanged: $(sql settlement_batch "SELECT count(*) FROM settlement_line WHERE file_key = '$key'"))" \
    || bad "a rerun writes a lost summary again" "exit $rc; $(grep -E 'Settlement|Summary|ERROR' "$log.3" | tail -n 4 | cut -c1-300)"
}

dlq_grew() { [ "$(topic_size payments.dlq)" -ge "$1" ]; }
g_dlq() {
  involved=(payment-process payments-history notifications-processor)
  local d0 g
  d0=$(topic_size payments.dlq)
  echo 'not a CloudEvent' | "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic payments >/dev/null 2>&1
  if poll 60 dlq_grew $((d0 + 3)); then
    pass "a malformed message goes to payments.dlq once per consumer group (3)"
  else
    bad "a malformed message goes to payments.dlq once per consumer group (3)" "payments.dlq: before $d0, now $(topic_size payments.dlq)"
  fi
  DLQ0=$(topic_size payments.dlq)
  for g in payment-process payments-history notifications-processor; do
    poll 30 caught_up "$g" && pass "  ... $g keeps consuming after it" \
      || bad "  ... $g keeps consuming after it" "$(lag_report "$g")"
  done
}

g_recovery() {
  involved=(payment-process core-integration)
  need_beneficiaries || return
  local stub id s start
  stub=$(curl -s -X POST "$WM/__admin/mappings" "${JSON[@]}" -d "{
    \"priority\": 1,
    \"request\": {\"method\": \"POST\", \"urlPath\": \"/core/v2/accounts/$SLOW/debits\"},
    \"response\": {\"status\": 200, \"headers\": {\"Content-Type\": \"application/json\"},
      \"body\": \"{\\\"mvId\\\": \\\"MV-{{randomValue length=8 type='NUMERIC'}}\\\", \\\"state\\\": \\\"P\\\"}\",
      \"fixedDelayMilliseconds\": 12000}}" | jq -r '.id // empty')
  if [ -z "$stub" ]; then bad "slow core debit (WireMock stub)" "WireMock did not accept the stub"; return; fi
  pay "$ANA" "$BEN" "$SLOW" "$(amount)" PEN
  if [ "$code" != 201 ]; then
    curl -s -X DELETE "$WM/__admin/mappings/$stub" >/dev/null
    bad "payment with a debit that times out" "HTTP $code $body"; return
  fi
  id=$paid
  note "payment $id: its debit times out in the core (12 s answer, 4 s read timeout)"
  if poll 240 process_stopped "$id" && process_is "$id" VERIFYING; then
    pass "uncertain debit: the process waits VERIFYING, nothing compensated"
    s=$(order_status "$id" "$ANA")
    [ "$s" = PROCESSING ] && pass "  ... the order stays PROCESSING" || bad "  ... the order stays PROCESSING" "status $s"
  else
    bad "uncertain debit: the process waits VERIFYING" "process status: $(process_status "$id")"
  fi
  curl -s -X DELETE "$WM/__admin/mappings/$stub" >/dev/null
  note "the core answers again; the recovery job resumes sagas idle for 2 minutes (every 60 s)..."
  start=$SECONDS
  if poll 300 process_final "$id" && process_is "$id" COMPLETED; then
    pass "the recovery job verifies the debit and completes the payment ($((SECONDS - start)) s)"
    s=$(order_status "$id" "$ANA")
    [ "$s" = COMPLETED ] && pass "  ... the order is COMPLETED" || bad "  ... the order is COMPLETED" "status $s"
    grep -q 'Resumed .* interrupted payment processes' "$root/.run/payment-process.log" \
      && pass "  ... payment-process logged the resumption" || bad "  ... payment-process logged the resumption" "no 'Resumed' line in .run/payment-process.log"
  else
    bad "the recovery job completes the payment" "process status after $((SECONDS - start)) s: $(process_status "$id")"
  fi
}

# ---------------------------------------------------------------------------------------------

for tool in curl jq docker; do command -v "$tool" >/dev/null || { echo "missing tool: $tool"; exit 1; }; done
groups=("$@")
[ ${#groups[@]} -eq 0 ] && groups=("${all_groups[@]}")
for g in "${groups[@]}"; do
  [[ " ${all_groups[*]} " == *" $g "* ]] || { echo "unknown group $g (one of: ${all_groups[*]})"; exit 2; }
done

if [ "${SCENARIOS_LOCAL:-1}" = 1 ]; then
  bash "$root/scripts/demo.sh" wait "${SCENARIOS_WAIT:-300}" || exit 1
fi
curl -s -o /dev/null "$WM/__admin/mappings" || { echo "WireMock does not answer on $WM: scripts/demo.sh up"; exit 1; }
tokens
DLQ0=$(topic_size payments.dlq)
AUDIT0=$(audit_objects); AUDIT0=${AUDIT0:-0}
AUDIT_TOPIC0=$(topic_size audit)
started=$SECONDS

for g in "${groups[@]}"; do
  [[ " ${SCENARIOS_SKIP:-} " == *" $g "* ]] && continue
  group=$g
  printf '\n== %s\n' "$g"
  tokens
  "g_$g"
done

printf '\n%d checks passed, %d failed (%d s)\n' "$passed" "$failed" $((SECONDS - started))
if [ "$failed" -gt 0 ]; then
  printf '\033[31mFAILED:\033[0m\n'
  printf '  %s\n' "${failures[@]}"
  exit 1
fi
printf '\033[32mOK\033[0m: every scenario passed\n'
