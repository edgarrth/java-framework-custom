# Demo de pagos

La demo reúne nueve servicios que consumen el framework. El flujo crea una orden desde el
canal, consulta al beneficiario, debita la cuenta en el core bancario, envía la transferencia
a la red de pagos y publica los estados para notificaciones, historial y liquidación.
Los nombres, cuentas y contratos externos son ficticios.

## Servicios

| Service | Role / structure + extensions / stack | Persistence | What it shows |
|---|---|---|---|
| `payments-ux` | ux / bff / reactive | – | Channel API, compositions with optional parts, token relay, Redis cache and idempotency, feature flags, masking, error translation |
| `payment-orders` | business / hexagonal + cqrs / servlet | Aurora | Domain rules, business calendar and cut-off, sequences, field encryption + blind index, transactional outbox, audit to MSK |
| `beneficiaries` | business / layered / servlet | DynamoDB | Repositories with cursor pagination, soft delete, optimistic locking, cache, idempotency on DynamoDB |
| `banking-core` | core / hexagonal / servlet | – | Anti-corruption layer: REST with client credentials, SOAP with WS-Security, Redis locks, audit to SQS |
| `payment-network-gateway` | gateway / hexagonal / servlet | – | Payment network isolation, API key from secrets, transfer idempotency with Redis, audit to SQS |
| `payment-process` | business / hexagonal + saga / servlet | Aurora | Saga with compensations and recovery, Kafka consumer, ShedLock, tenancy |
| `notifications-processor` | event-processor / eda | DynamoDB | Kafka and SQS handlers, inbox, notifications to SNS, audit archive on S3 with DynamoDB locks |
| `settlement-batch` | batch / batch / CronJob | Aurora | Spring Batch over a fixed-width S3 file, control totals, idempotent load, job exit codes |
| `payments-history` | business / hexagonal + cqrs / reactive | Aurora (R2DBC) | Read model fed by Kafka: reactive command and query buses, reactive transactions on R2DBC, Spring Data R2DBC with auditing, encrypted and money columns, R2DBC inbox |

## Flujos

1. `payments-ux` compone la entrada del canal con `beneficiaries` y `payment-orders`.
2. `payment-orders` publica eventos mediante outbox; `payment-process` coordina la saga.
3. `banking-core` adapta el sistema de cuentas y `payment-network-gateway` adapta la red externa.
4. `notifications-processor` procesa estados, notifica y archiva auditoría de negocio.
5. `payments-history` mantiene el modelo de lectura reactivo y `settlement-batch` procesa
   el archivo de liquidación.

## Ejecución local

Desde `axiz-demo`:

```bash
scripts/demo.sh up
scripts/demo.sh status
scripts/demo.sh smoke
scripts/demo.sh scenarios
scripts/demo.sh down
```

Docker Compose proporciona PostgreSQL, Valkey, Kafka, LocalStack, Keycloak, WireMock y
observabilidad. `kind/up.sh` ejecuta los servicios con sus charts en Kubernetes local.
Los secretos locales se generan para desarrollo; los entornos desplegados usan su proveedor
de secretos configurado.

El [README de la demo](https://github.com/edgarrth/java-framework-custom/blob/main/axiz-demo/README.md)
describe puertos, credenciales locales, contratos, colección Bruno, scripts y escenarios.
Las [guías de capacidades](capacidades/index.md) explican las APIs usadas por cada servicio.
