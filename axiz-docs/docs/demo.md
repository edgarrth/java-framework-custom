# Demo: procesador de pagos

`axiz-demo` es un procesador de pagos de **ocho servicios**, generados con `axiz new` y crecidos
con `axiz add` / `axiz generate`, que juntos usan **todas las capacidades del catálogo** (una prueba
de cobertura lo garantiza). Flujo de negocio: un cliente paga a un beneficiario frecuente desde la
web o la app; el pago se debita de su cuenta en el core bancario, se envía a la red interbancaria,
se notifica, se audita y se concilia con el archivo diario de liquidación.

Todos los nombres, cuentas y datos son ficticios.

| Servicio | Arquetipo · estilo · stack | Persistencia | Qué muestra |
|---|---|---|---|
| `payments-experience` | experience · bff · reactivo | – | API de canal, composición con partes opcionales, token relay, caché e idempotencia en Redis, feature flags, enmascarado, traducción de errores |
| `payments-business` | business · cqrs · servlet | Aurora | reglas de dominio, calendario y corte, secuencias, cifrado de campos con blind index, outbox, auditoría a MSK |
| `beneficiaries-crud` | crud · layered · servlet | DynamoDB | paginación por cursor, borrado lógico, bloqueo optimista, caché, idempotencia en DynamoDB |
| `core-integration` | integration · hexagonal · servlet | – | capa anticorrupción: REST con client credentials, SOAP con WS-Security, API key desde secretos, locks en Redis, auditoría a SQS |
| `payment-process` | process · hexagonal · servlet | Aurora | saga con compensaciones y recuperación, consumidor Kafka, ShedLock, tenancy |
| `notifications-processor` | event-processor · eda | DynamoDB | handlers de Kafka y SQS, inbox, notificaciones a SNS, archivo de auditoría en S3 con locks en DynamoDB |
| `settlement-batch` | batch · batch · CronJob | Aurora | Spring Batch sobre un archivo de ancho fijo en S3, totales de control, carga idempotente, códigos de salida |
| `payments-history` | business · cqrs · reactivo | Aurora (R2DBC) | modelo de lectura alimentado por Kafka: buses CQRS reactivos, transacciones reactivas sobre R2DBC, Spring Data R2DBC con auditoría y columnas cifradas y de dinero, inbox R2DBC |

## Flujos

`docs/architecture.drawio` tiene un diagrama C4 de contenedores por flujo (el que inicia a la
izquierda, flechas hacia la derecha):

1. **Pago desde el canal**: app → payments-experience → beneficiaries-crud y payments-business.
2. **Ejecución del pago**: payments-business → MSK → payment-process (saga) → core-integration →
   core bancario y red de pagos.
3. **Cierre, notificación y auditoría**: los cambios de estado y la auditoría llegan a
   notifications-processor (SNS, S3).
4. **Liquidación diaria**: archivo de la red en S3 → settlement-batch → Aurora y resumen en S3.
5. **Historial de pagos**: payments-business → MSK → payments-history → Aurora por R2DBC; los
   consumidores del API lo leen sin bloquear hilos (eventualmente consistente).

La saga de `payment-process`:

| Paso | Acción | Compensación |
|---|---|---|
| `processing` | orden → PROCESSING | orden → REJECTED |
| `debit` | débito en el core | reversa del débito |
| `transfer` | transferencia a la red (pivote) | – |
| `complete` | orden → COMPLETED (nunca hace fallar la saga; la conciliación la completa) | – |

## Correrlo

```bash
scripts/demo.sh up       # verifica Docker, JDK y puertos; levanta todo y espera 7/7 UP
scripts/demo.sh status   # estado de cada servicio
scripts/demo.sh smoke    # paga un beneficiario por la API de canal y espera COMPLETED
scripts/demo.sh scenarios   # todos los escenarios funcionales (unos 8 minutos)
scripts/demo.sh down
kind/up.sh               # o todo dentro de Kubernetes con los charts reales (puertos 9080, 9180, 9300)
```

| | |
|---|---|
| API de canal | http://localhost:8080 |
| Keycloak | http://localhost:8180 (realm `axiz`, usuarios `ana` y `luis`) |
| Grafana | http://localhost:3000 (dashboards base y *axiz · payments*) |
| WireMock | http://localhost:8089 (core bancario REST y SOAP, red de pagos) |

El core simulado responde `CB-51` (fondos insuficientes) para la cuenta `1910099999999` y `CB-62`
(cuenta bloqueada) para `1910088888888`; la red rechaza el CCI `00219100123456780052`, así se ven las
compensaciones de punta a punta. La colección de **Bruno** (`bruno/`) cubre autenticación, el flujo
de canal, llamadas máquina a máquina y casos de error.

## Escenarios funcionales

`scripts/demo.sh scenarios` verifica lo que el demo promete, más allá del pago del smoke: 86
verificaciones en 14 grupos, cada una con `PASS` o `FAIL`. Una falla muestra el motivo y las últimas
advertencias de los servicios del grupo.

| Grupo | Qué prueba |
|---|---|
| `beneficiaries` | dígitos de control del CCI, un beneficiario activo por cuenta, renombrar, borrado lógico, idempotencia sobre DynamoDB, paginación por cursor, enmascaramiento |
| `payment` | un pago de punta a punta: canal, saga, vista del proceso, historial, orden |
| `idempotency` | reintento con la misma llave, misma llave con otro cuerpo, sin llave |
| `rules` | duplicado, límite diario, feature flag de USD (mensajes en español e inglés), moneda distinta |
| `saga` | sin fondos, cuenta bloqueada, destino cerrado (se revierte el débito), cuenta de otro cliente |
| `security` | 401, datos de otro cliente (404/403), operaciones reservadas a payment-process, actuator |
| `integration` | core-integration: titular, crédito SOAP, movimiento inexistente, rechazo de la red |
| `events` | outbox, cifrado en la base, estados de las sagas, consumidores de Kafka al día, DLQ vacía, CloudEvents |
| `notifications` | notificaciones en SNS de pagos completados y rechazados |
| `audit` | auditoría por SQS archivada en S3 con su manifiesto, topic `audit`, DLQ de auditoría |
| `observability` | métricas en Prometheus y trazas en Tempo |
| `settlement` | el batch carga el archivo de ejemplo, no repite cargas, deja el resumen en S3 |
| `dlq` | un mensaje malformado termina en `payments.dlq`, una vez por consumidor |
| `recovery` | un débito con timeout deja la saga en `VERIFYING`; el job de recuperación la completa |

`scripts/demo.sh scenarios saga security` ejecuta solo esos grupos, y
`SCENARIOS_SKIP="recovery"` salta uno. Usan la infraestructura local, no kind.
