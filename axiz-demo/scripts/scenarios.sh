#!/bin/bash
# "condition && pass ... || bad ...": pass siempre termina con éxito, así que bad solo se ejecuta cuando falló la condición
# shellcheck disable=SC2015
# Escenarios funcionales de toda la demo, contra los servicios arrancados por scripts/demo.sh up.
# Mientras la prueba smoke paga una vez, esta suite verifica lo que promete la demo, un grupo a la vez:
#
#   beneficiaries  registro, dígitos de control del CCI, duplicados, renombrado, borrado lógico, páginas
#                  por cursor, enmascaramiento en el canal
#   payment        un pago de extremo a extremo: canal, saga, vista del proceso, historial, orden
#   idempotency    petición repetida, misma clave con otro cuerpo, clave ausente
#   rules          pago duplicado, límite diario, flag de moneda (con i18n), moneda no coincidente
#   saga           rechazos y compensaciones: sin fondos, cuenta bloqueada, destino cerrado
#                  (el débito se revierte), cuenta de otro cliente
#   security       401 sin token, datos de otro cliente (404/403), cambios de estado y movimientos
#                  de dinero reservados a payment-process, actuator protegido
#   integration    banking-core: crédito SOAP, movimiento desconocido, verificación del titular
#   events         outbox publicado, cuentas cifradas, estados de la saga, consumidores de Kafka y DLQ,
#                  cabeceras de CloudEvents
#   notifications  solicitudes de notificación en SNS para pagos completados y rechazados
#   audit          registro de auditoría: SQS -> archivo en S3 (con manifiesto), tópico de auditoría en Kafka, DLQ de auditoría
#   observability  métricas en Prometheus y trazas en Tempo (a través de Grafana): una traza desde la
#                  petición del canal hasta la saga
#   settlement     settlement-batch: carga un archivo nuevo por ejecución, reejecución idempotente, resumen
#                  en S3, un resumen perdido que una reejecución vuelve a escribir
#   dlq            un mensaje mal formado en el tópico de pagos termina en payments.dlq (cada consumidor)
#   recovery       un débito que excede el timeout deja la saga en VERIFYING; el job de recuperación la
#                  completa (tarda unos 4 minutos)
#
#   scripts/scenarios.sh                  todos los grupos, en ese orden (unos 8 minutos)
#   scripts/scenarios.sh saga security    solo esos grupos (se crean los datos que necesitan)
#   SCENARIOS_SKIP="recovery" scripts/scenarios.sh   todos los grupos salvo esos
#
# Cada verificación imprime PASS o FAIL; una verificación fallida muestra el motivo (estado, cuerpo) y las
# últimas advertencias y errores de los servicios involucrados. El resumen lista los fallos; código de salida 1
# cuando alguna verificación falló. Solo para ejecuciones locales: lee las bases de datos, Kafka, LocalStack y
# WireMock de local/compose.yaml.
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
KC_URL=${KC_URL:-http://localhost:8180}
EXP=http://localhost:8080
BUS=http://localhost:8081
CRUD=http://localhost:8082
CORE=http://localhost:8083
GATEWAY=http://localhost:8087
PROC=http://localhost:8084
HIST=http://localhost:8086
WM=http://localhost:8089
GRAFANA=${GRAFANA_URL:-http://localhost:3000}
compose=(docker compose -f "$root/local/compose.yaml")
JSON=(-H "Content-Type: application/json")
all_groups=(beneficiaries payment idempotency rules saga security integration events notifications
  audit observability settlement dlq recovery)

# cuentas y CCI del core y de la red simulados (local/wiremock)
ACCOUNT=1910012345678         # C-1001 (ana) es su titular
NO_FUNDS=1910099999999        # CB-51
BLOCKED=1910088888888         # CB-62
SLOW=1910077777777            # el grupo recovery hace que sus débitos excedan el timeout
CCI_LANDLORD=00219100123456789053
CCI_CLOSED=00219100123456780052   # la red rechaza las transferencias hacia ella (NET-R02)
CCI_USD=00219100123456781159
CCI_TEMP=00219100123456782256
CCI_LUIS=00319100987654321043

passed=0
failed=0
failures=()
group=""
involved=()
run_ids=()      # pagos creados por esta ejecución
used_amounts=" "

# ---------------------------------------------------------------------------------------------
# salida

pass() { passed=$((passed + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; return 0; }
bad() {
  failed=$((failed + 1))
  failures+=("[$group] $1")
  printf '  \033[31mFAIL\033[0m %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | head -n 20 | sed 's/^/         /'
  show_logs
}
note() { printf '       %s\n' "$*"; }

# últimas advertencias y errores de los servicios involucrados en el grupo actual
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

# call MÉTODO URL [args de curl...]: asigna $code, $body y $headers
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

# expect NOMBRE "<codes>" [condiciones jq sobre el cuerpo...]
expect() {
  local name=$1 codes=$2 q why=""
  shift 2
  [[ " $codes " == *" $code "* ]] || why="HTTP $code, expected $codes"
  if [ -z "$why" ]; then
    for q in "$@"; do
      jq -e "$q" <<<"$body" >/dev/null 2>&1 || { why="response does not satisfy: $q"; break; }
    done
  fi
  if [ -z "$why" ]; then pass "$name"; return 0; fi
  bad "$name" "$why
$( (jq -c . <<<"$body" 2>/dev/null || printf '%s' "$body") | cut -c1-700)"
  return 1
}

# check NOMBRE COMANDO...: PASS cuando el comando termina con éxito
check() {
  local name=$1 out
  shift
  if out=$("$@" 2>&1); then pass "$name"; return 0; fi
  bad "$name" "$out"
  return 1
}

# poll SEGUNDOS COMANDO...: reintenta cada 2 s hasta que el comando termina con éxito
poll() {
  local limit=$1 start=$SECONDS
  shift
  until "$@" >/dev/null 2>&1; do
    [ $((SECONDS - start)) -ge "$limit" ] && return 1
    sleep 2
  done
}

# ---------------------------------------------------------------------------------------------
# infraestructura de local/compose.yaml

sql() { "${compose[@]}" exec -T postgres psql -U app -d "$1" -tAc "$2" 2>&1; }
# JSON solo en stdout: la CLI también escribe avisos (banners de deprecación) en stderr
awsl() { "${compose[@]}" exec -T localstack awslocal "$@" 2>/dev/null; }
kafka() { local tool=$1; shift; "${compose[@]}" exec -T kafka "/opt/kafka/bin/$tool" --bootstrap-server localhost:9092 "$@" 2>/dev/null; }

# mensajes escritos en un tópico (suma de los offsets finales de sus particiones)
topic_size() { kafka kafka-get-offsets.sh --topic "$1" | awk -F: '{s += $3} END {print s + 0}'; }

# los consumidores del grupo están conectados y leyeron todo lo que hay en el tópico
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

# objetos archivados hoy bajo audit/. La CLI pagina list-objects-v2 y combina las páginas,
# lo que descarta KeyCount (imprimía None): por eso se cuentan los Contents combinados
audit_objects() {
  local n
  # shellcheck disable=SC2016 # JMESPath literal, not a shell expansion
  n=$(awsl s3api list-objects-v2 --bucket local-bucket --prefix "audit/$(date -u +%Y/%m/%d)/" \
    --query 'length(Contents || `[]`)' --output text | tr -dc '0-9')
  echo "${n:-0}"
}

# ---------------------------------------------------------------------------------------------
# tokens y datos

token_user() {
  curl -s "$KC_URL/realms/axiz/protocol/openid-connect/token" -d grant_type=password \
    -d client_id=payments-web -d username="$1" -d password="local-only-$1" | jq -r '.access_token // empty'
}
token_client() {
  curl -s "$KC_URL/realms/axiz/protocol/openid-connect/token" -d grant_type=client_credentials \
    -d client_id="$1" -d client_secret="$2" | jq -r '.access_token // empty'
}
# claim TOKEN NOMBRE: un claim del payload del JWT
claim() {
  local p
  p=$(cut -d. -f2 <<<"$1" | tr '_-' '/+')
  while [ $((${#p} % 4)) -ne 0 ]; do p+="="; done
  base64 -d <<<"$p" 2>/dev/null | jq -r --arg n "$2" '.[$n] // empty'
}
tokens_at=-1000
tokens() {
  # los tokens duran 15 minutos: se renuevan para cada grupo
  [ $((SECONDS - tokens_at)) -lt 300 ] && return 0
  ANA=$(token_user ana)
  LUIS=$(token_user luis)
  M2M=$(token_client payment-process local-only-payment-process)
  OTHER=$(token_client local-client local-only)
  if [ -z "$ANA" ] || [ -z "$LUIS" ] || [ -z "$M2M" ] || [ -z "$OTHER" ]; then
    printf '\033[31mFAILED: no tokens from Keycloak (%s)\033[0m\n' "$KC_URL" >&2
    exit 1
  fi
  # payment-process es multi-tenant: los tokens deben llevar el claim tenant (realm de esta demo)
  if [ "$(claim "$M2M" tenant)" != demo ]; then
    printf '\033[31mFAILED: the tokens have no claim "tenant": Keycloak runs an older realm.\033[0m\n' >&2
    echo "  scripts/demo.sh down && scripts/demo.sh up   (imports local/keycloak/axiz-realm.json again)" >&2
    exit 1
  fi
  tokens_at=$SECONDS
}

# beneficiary CLIENTE TOKEN ALIAS TITULAR CCI MONEDA: id del beneficiario activo con ese
# CCI, registrándolo si no existe
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

# un pago completado de ana (y uno rechazado), creados por los grupos payment y saga cuando
# no se ejecutaron previamente
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

# un importe aún no usado en esta ejecución: el mismo importe al mismo beneficiario el mismo día se
# rechaza como probable duplicado
amount() {
  local a
  while :; do
    a=$(printf '%d.%02d' $((10 + RANDOM % 90)) $((RANDOM % 100)))
    [[ "$used_amounts" == *" $a "* ]] || break
  done
  used_amounts+="$a "
  echo "$a"
}

# pay TOKEN BENEFICIARIO CUENTA IMPORTE MONEDA [IDEMPOTENCY-KEY] [TRACEPARENT]
pay() {
  local trace=()
  [ -z "${7:-}" ] || trace=(-H "traceparent: $7")
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $1" "${JSON[@]}" -H "Idempotency-Key: ${6:-$(key)}" \
    "${trace[@]}" \
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

# final_status ID TOKEN: espera (hasta 120 s) COMPLETED o REJECTED
final_status() {
  poll "${SCENARIOS_SAGA_WAIT:-120}" is_final "$1" "$2"
  order_status "$1" "$2"
}

process_view() { call GET "$PROC/v1/payment-processes/$1" -H "Authorization: Bearer $M2M"; }
process_status() { curl -s "$PROC/v1/payment-processes/$1" -H "Authorization: Bearer $M2M" | jq -r '.status // empty'; }
history_status() { curl -s "$HIST/v1/payments/$1" -H "Authorization: Bearer $ANA" | jq -r '.status // empty'; }
process_is() { [ "$(process_status "$1")" = "$2" ]; }
# la saga dejó de ejecutarse: VERIFYING o terminada
process_stopped() { [[ " VERIFYING COMPLETED REJECTED NEEDS_ATTENTION " == *" $(process_status "$1") "* ]]; }
process_final() { [[ " COMPLETED REJECTED NEEDS_ATTENTION " == *" $(process_status "$1") "* ]]; }
history_is() { [ "$(history_status "$1")" = "$2" ]; }
reserved() { sql payment_orders "SELECT COALESCE(SUM(reserved),0)::numeric(18,2) FROM payment_daily_usage WHERE customer_id='C-1001' AND currency='PEN'"; }

# journal de WireMock: reversiones solicitadas hasta el momento
reversals() {
  curl -s "$WM/__admin/requests?limit=1000" \
    | jq '[.requests[] | select(.request.method == "POST" and (.request.url | test("/reversal$")))] | length'
}
# id del movimiento que devolvió el core para el débito con referencia $1 (el número de operación)
movement_of() {
  curl -s "$WM/__admin/requests?limit=1000" | jq -r --arg op "$1" '
    [.requests[] | select(.request.url | test("/debits$"))
      | select((.request.body | fromjson? | .ref) == $op)
      | .response.body | fromjson? | .mvId // empty] | first // empty'
}

# ---------------------------------------------------------------------------------------------
# grupos

g_beneficiaries() {
  involved=(beneficiaries payments-ux)
  local id
  need_beneficiaries || return
  pass "beneficiaries of ana (Landlord, closed account, USD) and luis registered or reused"

  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d '{"alias":"Mal","holderName":"X","cci":"12345678901234567890","currency":"PEN"}'
  expect "CCI with wrong check digits is rejected (422)" 422 '.code == "PAYMENTS-BENEFICIARIES-0001"'

  call POST "$CRUD/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
    -H "Idempotency-Key: $(key)" -d "{\"alias\":\"Otra vez\",\"holderName\":\"Ana Torres\",\"cci\":\"$CCI_LANDLORD\",\"currency\":\"PEN\"}"
  expect "one active beneficiary per account (409)" 409 '.code == "PAYMENTS-BENEFICIARIES-0002"'

  # ciclo de vida sobre un beneficiario propio (se elimina de nuevo al final)
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
  # el cliente perdió la respuesta y reintenta: la idempotencia en DynamoDB devuelve el mismo beneficiario
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
  expect "a malformed cursor is rejected (400)" 400 '.code == "PAYMENTS-BENEFICIARIES-0004"'

  call GET "$EXP/v1/beneficiaries?size=20" -H "Authorization: Bearer $ANA"
  expect "channel list masks accounts and names" 200 '.items | length > 0' \
    'all(.items[]; (.creditorCci | test("^\\*+[0-9]{4}$")) and (.holderName | contains("*")))'
}

g_payment() {
  involved=(payments-ux payment-orders payment-process banking-core payment-network-gateway payments-history)
  need_beneficiaries || return
  PAY_KEY=$(key)
  PAY_AMOUNT=$(amount)
  # Una traza de ingreso conocida identifica este fixture exitoso incluso después de ejecutar los escenarios de rechazo.
  PAY_TRACE=$(key); PAY_TRACE=${PAY_TRACE//-/}
  local parent; parent=$(key); parent=${parent//-/}
  pay "$ANA" "$BEN" "$ACCOUNT" "$PAY_AMOUNT" PEN "$PAY_KEY" "00-$PAY_TRACE-${parent:0:16}-01"
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
  expect "payment-orders order readable by payments:process" 200 '.status == "COMPLETED"'
}

g_idempotency() {
  involved=(payments-ux)
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
  involved=(payments-ux payment-orders)
  need_payment
  need_beneficiaries || return
  if [ -n "${PAY:-}" ]; then
    pay "$ANA" "$BEN" "$ACCOUNT" "$PAY_AMOUNT" PEN
    expect "same amount to the same beneficiary today: probable duplicate (409)" 409 \
      '.code == "PAYMENTS-PAYMENTSUX-0004"'
  fi
  pay "$ANA" "$BEN" "$ACCOUNT" 30000.01 PEN
  expect "above the daily limit of 30 000 PEN (422)" 422 '.code == "PAYMENTS-PAYMENTSUX-0003"'
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -H "Accept-Language: es-PE" -d "{\"beneficiaryId\":\"$USDBEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"USD\"}"
  expect "USD payments are off (feature flag payments-usd), message in Spanish" 422 \
    '.code == "PAYMENTS-PAYMENTSUX-0002"' '.detail | test("no están disponibles")'
  call POST "$EXP/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -H "Accept-Language: en" -d "{\"beneficiaryId\":\"$USDBEN\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"USD\"}"
  expect "  ... and in English with Accept-Language: en (i18n)" 422 '.detail | test("are not available")'
  pay "$ANA" "$USDBEN" "$ACCOUNT" 10 PEN
  expect "PEN to a USD beneficiary: currency mismatch (422)" 422 '.code == "PAYMENTS-PAYMENTSUX-0001"'
}

# rejected NOMBRE TOKEN BENEFICIARIO CUENTA PASO-FALLIDO: paga, espera REJECTED y verifica el proceso
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
  involved=(payment-process banking-core payment-network-gateway payment-orders)
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
    involved=(payment-process banking-core payment-network-gateway payment-orders)
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
  involved=(payments-ux payment-orders payments-history beneficiaries banking-core)
  need_payment
  [ -n "${PAY:-}" ] || return
  call GET "$EXP/v1/payments/$PAY"
  expect "without a token (401)" 401 '.code == "AXZ-SEC-0401"'
  call GET "$EXP/v1/payments/$PAY" -H "Authorization: Bearer not-a-token"
  expect "with an invalid token (401)" 401
  call GET "$EXP/v1/payments/$PAY" -H "Authorization: Bearer $LUIS"
  expect "luis reads ana's payment in the channel: not found (404)" 404
  call GET "$BUS/v1/payment-orders/$PAY" -H "Authorization: Bearer $LUIS"
  expect "  ... in payment-orders: not found (404)" 404
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
  involved=(banking-core payment-network-gateway)
  call POST "$CORE/v1/debits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"accountId\":\"$ACCOUNT\",\"amount\":10,\"currency\":\"PEN\",\"holderId\":\"C-1002\",\"reference\":\"X\"}"
  expect "debit of an account of another holder (422, checked by the core)" 422 '.code == "PAYMENTS-BANKINGCORE-0004"'
  call POST "$CORE/v1/credits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"accountId\":\"$ACCOUNT\",\"amount\":15.50,\"currency\":\"PEN\",\"holderId\":\"C-1001\",\"reference\":\"SCN-$RANDOM\"}"
  expect "credit through the SOAP service of the core (WS-Security)" 201 '.movementId | length > 0'
  call POST "$CORE/v1/debits/MV-404/reversal" -H "Authorization: Bearer $M2M" -H "Idempotency-Key: $(key)"
  expect "reversal of an unknown movement (404)" 404
  call POST "$GATEWAY/v1/transfers" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
    -d "{\"creditorCci\":\"$CCI_CLOSED\",\"amount\":10,\"currency\":\"PEN\",\"reference\":\"SCN-$RANDOM\"}"
  expect "transfer to a closed account: rejected by the network (422)" 422 '.code == "PAYMENTS-NETWORKGATEWAY-0001"'
}

g_events() {
  involved=(payment-orders payment-process payments-history notifications-processor)
  local ids g n bad_sagas
  need_payment
  sleep 3
  n=$(sql payment_orders "SELECT count(*) FROM axiz_outbox WHERE status <> 'PUBLISHED'")
  [ "$n" = 0 ] && pass "outbox of payment-orders: every event PUBLISHED" \
    || bad "outbox of payment-orders: every event PUBLISHED" "not published: $n
$(sql payment_orders "SELECT event_type, status, attempts, last_error FROM axiz_outbox WHERE status <> 'PUBLISHED' LIMIT 5")"
  if [ -n "${PAY:-}" ]; then
    n=$(sql payment_orders "SELECT count(*) FROM payment_order WHERE id = '$PAY' AND debtor_account LIKE 'axz1:%' AND creditor_cci LIKE 'axz1:%'")
    [ "$n" = 1 ] && pass "accounts stored encrypted (axz1:...)" \
      || bad "accounts stored encrypted (axz1:...)" "$(sql payment_orders "SELECT left(debtor_account,12), left(creditor_cci,12) FROM payment_order WHERE id = '$PAY'")"
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
  # vacía la cola de sondeo (también contiene las notificaciones de ejecuciones previas) hasta que aparecen ambas
  local entries
  start=$SECONDS
  while [ $((SECONDS - start)) -lt 90 ]; do
    out=$(awsl sqs receive-message --queue-url "$q" --max-number-of-messages 10 --wait-time-seconds 2)
    entries=$(jq -c '[.Messages[]? | {Id: .MessageId, ReceiptHandle}]' <<<"$out" 2>/dev/null)
    [ -n "$entries" ] && [ "$entries" != "[]" ] \
      && awsl sqs delete-message-batch --queue-url "$q" --entries "$entries" >/dev/null
    # los CloudEvents estructurados (SNS/SQS) llevan el payload en data_base64: se decodifica para buscar en él
    seen+=$(jq -r '.Messages[]?.Body | ., ((fromjson? | .data_base64? // empty) | @base64d)' \
      <<<"$out" 2>/dev/null)
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
  involved=(banking-core notifications-processor payment-orders)
  local n
  need_payment
  if poll 90 audit_grew; then
    pass "audit of banking-core archived on S3 by notifications-processor (SQS -> S3): $(audit_objects) objects today"
  else
    bad "audit of banking-core archived on S3 (SQS -> S3)" "objects under audit/$(date -u +%Y/%m/%d)/: before $AUDIT0, now $(audit_objects)"
  fi
  awsl s3 ls "s3://local-bucket/audit/$(date -u +%Y/%m/%d)/_manifest.json" | grep -q _manifest \
    && pass "  ... with the daily manifest (counts per service, DynamoDB lock)" \
    || bad "  ... with the daily manifest" "no audit/$(date -u +%Y/%m/%d)/_manifest.json"
  poll 30 audit_topic_grew && pass "audit of payment-orders on the Kafka topic audit (outbox)" \
    || bad "audit of payment-orders on the Kafka topic audit" "messages before $AUDIT_TOPIC0, now $(topic_size audit)"
  n=$(awsl sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/axiz-demo-audit-dlq \
    --attribute-names ApproximateNumberOfMessages --query Attributes.ApproximateNumberOfMessages --output text | tr -dc '0-9')
  [ "${n:-0}" = 0 ] && pass "audit DLQ empty" || bad "audit DLQ empty" "$n messages in axiz-demo-audit-dlq"
}

grafana() {
  local out credentials
  credentials=$(printf '%s' "${GRAFANA_USERNAME:-admin}:${GRAFANA_PASSWORD:-admin}" | base64 | tr -d '\n')
  out=$(curl -s -w '\n%{http_code}' "$GRAFANA$1")
  # primero el acceso anónimo (otel-lgtm) y luego la cuenta de administración por defecto
  if [ "${out##*$'\n'}" != 200 ]; then out=$(printf 'header = "Authorization: Basic %s"\n' "$credentials" | curl --config - -s -w '\n%{http_code}' "$GRAFANA$1"); fi
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
# log_trace SERVICIO REGEX: trace ID de la última línea de .run/SERVICE.log que coincide con REGEX (el
# patrón de log imprime [traceId-spanId] del span actual; vacío cuando la línea se ejecutó fuera de una traza)
log_text() {
  # Las ejecuciones locales usan logs de texto; los mismos servicios en kind usan logs JSON estructurados.
  jq -Rr '. as $raw | try (fromjson |
    "[\(.trace_id // .traceId // "")-\(.span_id // .spanId // "")] \(.logger_name // "") : \(.message // "")") catch $raw' \
    "$root/.run/$1.log" 2>/dev/null
}
log_trace() {
  log_text "$1" | grep -E "$2" | grep -oE '\[[0-9a-f]{32}-[0-9a-f]{16}\]' | tail -1 | cut -c2-33
}
# trace_services TRACE-ID: nombres de servicio de los spans que Tempo guarda para esa traza
trace_services() {
  grafana "/api/datasources/proxy/uid/$TEMPO_UID/api/traces/$1" | jq -r '
    [ (.batches // .resourceSpans // [])[] | .resource.attributes[]?
      | select(.key == "service.name") | .value.stringValue ] | unique | join(" ")' 2>/dev/null
}
# trace_has TRACE-ID SERVICIO...: la traza contiene spans de cada servicio
trace_has() {
  local t=$1 have s; shift
  have=" $(trace_services "$t") "
  for s in "$@"; do [[ "$have" == *" $s "* ]] || return 1; done
}
# grafana_datasources: asigna PROM_UID y TEMPO_UID; false hasta que Grafana lista ambos (una sola
# petición puede encontrar a Grafana ocupado o reiniciándose, por eso la verificación sondea en lugar de fallar)
grafana_datasources() {
  local ds
  ds=$(grafana /api/datasources)
  PROM_UID=$(jq -r '.[]? | select(.type == "prometheus") | .uid' <<<"$ds" 2>/dev/null | head -1)
  TEMPO_UID=$(jq -r '.[]? | select(.type == "tempo") | .uid' <<<"$ds" 2>/dev/null | head -1)
  [ -n "$PROM_UID" ] && [ -n "$TEMPO_UID" ]
}
g_observability() {
  involved=()
  if ! poll 90 grafana_datasources; then
    bad "Grafana datasources (Prometheus, Tempo)" "$GRAFANA/api/health: HTTP $(curl -s -o /dev/null -w '%{http_code}' "$GRAFANA/api/health"); /api/datasources: $(grafana /api/datasources | cut -c1-300)"
    return
  fi
  need_payment
  local q='sum({__name__=~"axiz_events(_total)?", event="payment.order.created"})'
  poll 120 prom_positive "$q" && pass "business metrics in Prometheus (axiz events: payment.order.created)" \
    || bad "business metrics in Prometheus" "$q = $(promql "$q")"
  q='sum({__name__=~"http_server_request.*_count", job=~".*payments-ux.*"})'
  poll 60 prom_positive "$q" && pass "HTTP server metrics of payments-ux (OTLP)" \
    || bad "HTTP server metrics of payments-ux (OTLP)" "$q = $(promql "$q")"
  # Se sigue el pago exitoso conocido. La petición más reciente puede ser un rechazo que, correctamente,
  # nunca llega a la red de pagos. Los logs siguen demostrando que cada servicio propagó la traza.
  local t
  t=$(log_trace payment-process "\\[$PAY_TRACE-.*client=banking-core ")
  if [ -z "$t" ]; then
    bad "payment-process calls banking-core inside a trace" \
      "no 'client=banking-core' line with trace $PAY_TRACE of completed payment $PAY"
  elif ! log_text banking-core | grep "\[$t-" >/dev/null; then
    bad "banking-core continues the trace of payment-process (HTTP traceparent)" \
      "trace $t of payment-process is not in .run/banking-core.log; its last request line:
$(grep -E 'axiz.http .*path=/v1/' "$root/.run/banking-core.log" | tail -1 | cut -c1-400)"
  else
    pass "banking-core continues the trace of payment-process (trace $t in both logs)"
    poll 60 trace_has "$t" payment-process banking-core payment-network-gateway \
      && pass "  ... and Tempo holds that trace with the process, core and gateway" \
      || bad "  ... and Tempo holds that trace with the process, core and gateway" "services in trace $t: $(trace_services "$t")"
  fi
  # la petición que registra el pago y su saga forman una sola traza: el outbox conserva el
  # contexto de la escritura, el relay lo continúa y Kafka lo lleva a los consumidores
  local want=(payments-ux payment-orders payment-process banking-core payment-network-gateway)
  t=$(log_trace payment-orders "\\[$PAY_TRACE-.*path=/v1/payment-orders status=201")
  if [ -z "$t" ]; then
    bad "one trace from the channel request to the saga" \
      "no 'path=/v1/payment-orders status=201' line with trace $PAY_TRACE of completed payment $PAY"
  else
    poll 90 trace_has "$t" "${want[@]}" \
      && pass "one trace from the channel request to the saga: ${want[*]}" \
      || bad "one trace from the channel request to the saga: ${want[*]}" \
        "trace $t of completed payment $PAY has: $(trace_services "$t")"
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
    timeout 300 java -Djava.security.egd=file:/dev/./urandom --enable-native-access=ALL-UNNAMED ${AXIZ_JAVA_OPTS:--XX:TieredStopAtLevel=1} -jar "$jar" \
      --spring.profiles.active=local,platform --settlement.input-key="$key" > "$log" 2>&1
  )
}
# un archivo de liquidación propio para esta ejecución: el de ejemplo con ids de extremo a extremo que nadie
# cargó previamente (mismos anchos, importes y totales de control). Cada ejecución verifica una carga nueva,
# su reejecución y su resumen, sea cual sea el estado que ejecuciones previas dejaron en Aurora o S3
# (LocalStack no conserva datos entre reinicios, PostgreSQL sí)
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
  # 8 dígitos reemplazan los 8 caracteres de "20261011" en el id de extremo a extremo
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
  # el bucket pierde el resumen (como cuando LocalStack se reinicia): una reejecución lo escribe de nuevo desde Aurora
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
  involved=(payment-process banking-core payment-network-gateway)
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
  # VERIFYING solo aparece cuando el orquestador confirma que el resultado del débito es incierto
  # (agotados los reintentos del cliente HTTP); un paso en curso se ve como RUNNING. La demora del
  # core se mantiene hasta entonces para que el reintento no complete el débito.
  if poll 240 process_stopped "$id" && process_is "$id" VERIFYING \
    && [ "$(curl -s "$PROC/v1/payment-processes/$id" -H "Authorization: Bearer $M2M" | jq -r '.failedStep // empty')" = debit ]; then
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
