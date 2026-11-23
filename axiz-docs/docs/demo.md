# Demo de pagos

La demo reúne nueve servicios que consumen el framework. El flujo crea una orden desde el
canal, consulta al beneficiario, debita la cuenta en el core bancario, envía la transferencia
a la red de pagos y publica los estados para notificaciones, historial y liquidación.
Los nombres, cuentas y contratos externos son ficticios.

## Servicios

| Servicio | Rol / estructura + extensiones / stack | Persistencia | Qué muestra |
|---|---|---|---|
| `payments-ux` | ux / bff / reactive | Ninguno | API de canal, composiciones con partes opcionales, relay de tokens, caché e idempotencia en Redis, feature flags, enmascaramiento, traducción de errores |
| `payment-orders` | business / hexagonal + cqrs / servlet | Aurora | Reglas de dominio, calendario de negocio y hora de corte, secuencias, cifrado de campos + índice ciego, outbox transaccional, auditoría en MSK |
| `beneficiaries` | business / layered / servlet | DynamoDB | Repositorios con paginación por cursor, borrado lógico, bloqueo optimista, caché, idempotencia en DynamoDB |
| `banking-core` | core / hexagonal / servlet | Ninguno | Capa anticorrupción: REST con client credentials, SOAP con WS-Security, locks en Redis, auditoría en SQS |
| `payment-network-gateway` | gateway / hexagonal / servlet | Ninguno | Aislamiento de la red de pagos, API key desde secretos, idempotencia de transferencias con Redis, auditoría en SQS |
| `payment-process` | business / hexagonal + saga / servlet | Aurora | Saga con compensaciones y recuperación, consumidor Kafka, ShedLock, tenancy |
| `notifications-processor` | event-processor / eda | DynamoDB | Handlers de Kafka y SQS, inbox, notificaciones a SNS, archivo de auditoría en S3 con locks de DynamoDB |
| `settlement-batch` | batch / batch / CronJob | Aurora | Spring Batch sobre un archivo de ancho fijo en S3, totales de control, carga idempotente, códigos de salida del job |
| `payments-history` | business / hexagonal + cqrs / reactive | Aurora (R2DBC) | Modelo de lectura alimentado por Kafka: buses reactivos de comandos y consultas, transacciones reactivas sobre R2DBC, Spring Data R2DBC con auditoría, columnas cifradas y monetarias, inbox R2DBC |

## Flujos

1. `payments-ux` compone la entrada del canal con `beneficiaries` y `payment-orders`.
2. `payment-orders` publica eventos mediante outbox; `payment-process` coordina la saga.
3. `banking-core` adapta el sistema de cuentas y `payment-network-gateway` adapta la red externa.
4. `notifications-processor` procesa estados, notifica y archiva auditoría de negocio.
5. `payments-history` mantiene el modelo de lectura reactivo y `settlement-batch` procesa
   el archivo de liquidación.

## Ejecución local

La demo se ejecuta de dos formas desde la raíz del repositorio, con la misma infraestructura en
Docker Compose (PostgreSQL, Valkey, Kafka, LocalStack, Keycloak, WireMock y observabilidad).
Con los servicios en el host, que se pueden depurar y que ejecutan los escenarios funcionales:

```bash
axiz-demo/scripts/demo.sh up
axiz-demo/scripts/demo.sh status
axiz-demo/scripts/demo.sh down
```

Con todo en Docker Compose (infraestructura, imágenes de los servicios, secretos y datos), en un
solo comando que imprime todas las rutas al terminar:

```bash
axiz-demo/scripts/compose.sh up --build
axiz-demo/scripts/compose.sh up --no-build
axiz-demo/scripts/compose.sh down
```

En ambos casos, `axiz-demo/scripts/curl-tests.sh` recorre todos los endpoints y
`axiz-demo/scripts/test-all.sh` ejecuta todas las pruebas de la demo. `kind/up.sh` ejecuta los
servicios con sus charts en Kubernetes local.
Los secretos locales se generan para desarrollo; los entornos desplegados usan su proveedor
de secretos configurado.

El [README de la demo](https://github.com/edgarrth/java-framework-custom/blob/main/axiz-demo/README.md)
describe puertos, credenciales locales, contratos, colección Bruno, scripts y escenarios.
Las [guías de capacidades](capacidades/index.md) explican las APIs usadas por cada servicio.
