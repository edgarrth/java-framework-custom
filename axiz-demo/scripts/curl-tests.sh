#!/bin/bash
# Invoca cada endpoint REST de la demo en el orden del flujo de pago y verifica el estado HTTP
# esperado. Funciona con las dos formas de ejecutar la demo (scripts/demo.sh up o
# scripts/compose.sh up), desde cualquier directorio:
#   axiz-demo/scripts/curl-tests.sh            imprime cada petición, estado y respuesta
#   QUIET=1 axiz-demo/scripts/curl-tests.sh    imprime una línea por verificación
# Código de salida 0 solo cuando todas las verificaciones pasan.
set -Eeuo pipefail
trap 'printf "\\033[31m%s: unexpected failure (exit %s) at line %s: %s\\033[0m\\n" "${0##*/}" "$?" "$LINENO" "$BASH_COMMAND" >&2' ERR
KC=${KC_URL:-http://localhost:8180}
UX=http://localhost:8080 ORDERS=http://localhost:8081 BENEFICIARIES=http://localhost:8082
CORE=http://localhost:8083 PROCESS=http://localhost:8084 HISTORY=http://localhost:8086
GATEWAY=http://localhost:8087
ACCOUNT=1910012345678            # cuenta del cliente C-1001 (ana) en el core simulado
CCI=00219100123456789053         # cuenta de destino que acepta la red simulada
CCI_CLOSED=00219100123456780052  # cuenta de destino que la red rechaza (cerrada)
for tool in curl jq; do command -v "$tool" >/dev/null || { echo "missing tool: $tool"; exit 1; }; done

passed=0 failed=0
key() { cat /proc/sys/kernel/random/uuid; }
# check "descripción" estado-esperado MÉTODO URL [args de curl...]: asigna $body y $code
check() {
  local what=$1 expected=$2 method=$3 url=$4 out
  shift 4
  out=$(curl -sS --max-time 30 -w '\n%{http_code}' -X "$method" "$url" "$@" 2>&1) || true
  code=${out##*$'\n'}
  body=${out%$'\n'*}
  if [[ " $expected " == *" $code "* ]]; then
    passed=$((passed + 1)); printf '\033[32mPASS\033[0m %s (%s %s -> %s)\n' "$what" "$method" "$url" "$code"
  else
    failed=$((failed + 1)); printf '\033[31mFAIL\033[0m %s (%s %s -> %s, expected %s)\n' "$what" "$method" "$url" "$code" "$expected"
  fi
  [ -n "${QUIET:-}" ] || { [ -n "$body" ] && jq -C . <<<"$body" 2>/dev/null | head -20 || printf '%s\n' "${body:0:400}"; echo; }
}
# field "descripción" expresión-jq esperado: verifica un valor del cuerpo de la última respuesta
field() {
  local actual
  actual=$(jq -r "$2 // empty" <<<"$body" 2>/dev/null || true)
  if [ "$actual" = "$3" ]; then
    passed=$((passed + 1)); printf '\033[32mPASS\033[0m %s (%s = %s)\n' "$1" "$2" "$actual"
  else
    failed=$((failed + 1)); printf '\033[31mFAIL\033[0m %s (%s = %s, expected %s)\n' "$1" "$2" "${actual:-none}" "$3"
  fi
}
token() {
  curl -sS "$KC/realms/axiz/protocol/openid-connect/token" "$@" | jq -r '.access_token // empty'
}
JSON=(-H 'Content-Type: application/json')

# Tokens de acceso del Keycloak local: dos clientes (password grant del cliente del canal web,
# solo local) y la cuenta de servicio de payment-process (client credentials).
ANA=$(token -d grant_type=password -d client_id=payments-web -d username=ana -d password=local-only-ana)
LUIS=$(token -d grant_type=password -d client_id=payments-web -d username=luis -d password=local-only-luis)
M2M=$(token -d grant_type=client_credentials -d client_id=payment-process -d client_secret=local-only-payment-process)
[ -n "$ANA" ] && [ -n "$LUIS" ] && [ -n "$M2M" ] || { echo "Keycloak did not issue the tokens ($KC)"; exit 1; }

# beneficiaries
check "a beneficiary is registered for C-1001 (409 when it already exists)" "201 409" \
  POST "$BENEFICIARIES/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
  -H "Idempotency-Key: $(key)" -d "{\"alias\":\"Landlord\",\"holderName\":\"Ana Torres\",\"cci\":\"$CCI\",\"currency\":\"PEN\"}"
check "the beneficiaries of C-1001 are listed (cursor pagination)" 200 \
  GET "$BENEFICIARIES/v1/customers/C-1001/beneficiaries?size=20" -H "Authorization: Bearer $ANA"
beneficiary=$(jq -r --arg cci "$CCI" '[.items[] | select(.cci == $cci)][0].id // empty' <<<"$body")
check "one beneficiary is read" 200 \
  GET "$BENEFICIARIES/v1/customers/C-1001/beneficiaries/$beneficiary" -H "Authorization: Bearer $ANA"
check "the alias of the beneficiary is renamed" 200 \
  PUT "$BENEFICIARIES/v1/customers/C-1001/beneficiaries/$beneficiary" -H "Authorization: Bearer $ANA" \
  "${JSON[@]}" -d '{"alias":"Landlord"}'
check "a temporary beneficiary is registered (409 when it already exists)" "201 409" \
  POST "$BENEFICIARIES/v1/customers/C-1001/beneficiaries" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
  -H "Idempotency-Key: $(key)" -d "{\"alias\":\"Temporal\",\"holderName\":\"Pedro Ruiz\",\"cci\":\"$CCI_CLOSED\",\"currency\":\"PEN\"}"
check "the temporary beneficiary is located" 200 \
  GET "$BENEFICIARIES/v1/customers/C-1001/beneficiaries?size=20" -H "Authorization: Bearer $ANA"
temporary=$(jq -r --arg cci "$CCI_CLOSED" '[.items[] | select(.cci == $cci)][0].id // empty' <<<"$body")
check "the temporary beneficiary is removed (soft delete)" 204 \
  DELETE "$BENEFICIARIES/v1/customers/C-1001/beneficiaries/$temporary" -H "Authorization: Bearer $ANA"

# canal (payments-ux) y la saga de pago
check "the channel lists the beneficiaries of the signed-in customer (masked)" 200 \
  GET "$UX/v1/beneficiaries" -H "Authorization: Bearer $ANA"
pay_key=$(key)
amount="$((RANDOM % 900 + 100)).$((RANDOM % 90 + 10))"
pay_body="{\"beneficiaryId\":\"$beneficiary\",\"debtorAccount\":\"$ACCOUNT\",\"amount\":$amount,\"currency\":\"PEN\",\"description\":\"Rent\"}"
check "the customer pays the beneficiary through the channel" 201 \
  POST "$UX/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $pay_key" -d "$pay_body"
payment=$(jq -r '.paymentId // empty' <<<"$body")
check "the same request with the same Idempotency-Key replays the first answer" 201 \
  POST "$UX/v1/payments" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $pay_key" -d "$pay_body"
for _ in $(seq 1 60); do
  status=$(curl -s -H "Authorization: Bearer $ANA" "$UX/v1/payments/$payment" | jq -r '.status // empty')
  [ "$status" = COMPLETED ] || [ "$status" = REJECTED ] && break
  sleep 2
done
check "the channel reads the payment" 200 \
  GET "$UX/v1/payments/$payment" -H "Authorization: Bearer $ANA"
field "the saga completes the payment" .status COMPLETED

# órdenes de pago y proceso de pago
check "the payment order is read in payment-orders" 200 \
  GET "$ORDERS/v1/payment-orders/$payment" -H "Authorization: Bearer $ANA"
check "the payment orders of C-1001 are listed" 200 \
  GET "$ORDERS/v1/payment-orders?customerId=C-1001&page=0&size=5" -H "Authorization: Bearer $ANA"
check "a customer cannot move an order to another status (403)" 403 \
  POST "$ORDERS/v1/payment-orders/$payment/status" -H "Authorization: Bearer $ANA" "${JSON[@]}" \
  -d '{"status":"COMPLETED","reason":"x"}'
check "the payment process (saga) of the order is read" 200 \
  GET "$PROCESS/v1/payment-processes/$payment" -H "Authorization: Bearer $M2M"
field "the saga of the order ended" .status COMPLETED
check "a payment order is registered directly in payment-orders" 201 \
  POST "$ORDERS/v1/payment-orders" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"customerId\":\"C-1001\",\"debtorAccount\":\"$ACCOUNT\",\"creditorCci\":\"$CCI\",\"amount\":$((RANDOM % 900 + 100)).25,\"currency\":\"PEN\",\"description\":\"Direct\"}"
check "a payment process for an unknown order ends rejected" 201 \
  POST "$PROCESS/v1/payment-processes" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"paymentOrderId\":\"$(key)\",\"operationNumber\":\"OP-CURL-1\",\"debtorAccount\":\"$ACCOUNT\",\"creditorCci\":\"$CCI\",\"amount\":1.00,\"currency\":\"PEN\",\"customerId\":\"C-1001\"}"
field "the saga of an unknown order is rejected" .status REJECTED

# core bancario y red de pagos (servicio a servicio)
check "the core debits the account of its holder" 201 \
  POST "$CORE/v1/debits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"accountId\":\"$ACCOUNT\",\"amount\":1.00,\"currency\":\"PEN\",\"holderId\":\"C-1001\",\"reference\":\"CURL-$RANDOM\"}"
movement=$(jq -r '.movementId // empty' <<<"$body")
check "the debit is reversed (saga compensation)" 201 \
  POST "$CORE/v1/debits/$movement/reversal" -H "Authorization: Bearer $M2M" -H "Idempotency-Key: $(key)"
check "a debit of an account of another holder is rejected (422)" 422 \
  POST "$CORE/v1/debits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"accountId\":\"$ACCOUNT\",\"amount\":1.00,\"currency\":\"PEN\",\"holderId\":\"C-1002\",\"reference\":\"CURL-$RANDOM\"}"
check "the core credits the account through its SOAP service" 201 \
  POST "$CORE/v1/credits" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"accountId\":\"$ACCOUNT\",\"amount\":1.00,\"currency\":\"PEN\",\"holderId\":\"C-1001\",\"reference\":\"CURL-$RANDOM\"}"
check "the network accepts a transfer" 201 \
  POST "$GATEWAY/v1/transfers" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"creditorCci\":\"$CCI\",\"amount\":1.00,\"currency\":\"PEN\",\"reference\":\"CURL-$RANDOM\"}"
check "the network rejects a transfer to a closed account (422)" 422 \
  POST "$GATEWAY/v1/transfers" -H "Authorization: Bearer $M2M" "${JSON[@]}" -H "Idempotency-Key: $(key)" \
  -d "{\"creditorCci\":\"$CCI_CLOSED\",\"amount\":1.00,\"currency\":\"PEN\",\"reference\":\"CURL-$RANDOM\"}"

# modelo de lectura del historial
for _ in $(seq 1 30); do
  [ "$(curl -s -H "Authorization: Bearer $ANA" "$HISTORY/v1/payments/$payment" | jq -r '.status // empty')" = COMPLETED ] && break
  sleep 2
done
check "the history read model holds the payment" 200 \
  GET "$HISTORY/v1/payments/$payment" -H "Authorization: Bearer $ANA"
field "the history projects the final status" .status COMPLETED
check "the payments of C-1001 are listed, latest change first" 200 \
  GET "$HISTORY/v1/customers/C-1001/payments?page=0&size=5" -H "Authorization: Bearer $ANA"

# seguridad
check "a request without a token is rejected (401)" 401 GET "$UX/v1/beneficiaries"
check "another customer cannot read the payment (404)" 404 \
  GET "$UX/v1/payments/$payment" -H "Authorization: Bearer $LUIS"
check "a customer token cannot debit the core (403)" 403 \
  POST "$CORE/v1/debits" -H "Authorization: Bearer $ANA" "${JSON[@]}" -H "Idempotency-Key: $(key)" -d '{}'

printf '\n%s passed, %s failed\n' "$passed" "$failed"
[ "$failed" = 0 ]
