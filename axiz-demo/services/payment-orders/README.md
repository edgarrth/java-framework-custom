# Payment Orders

Dueño de las órdenes de pago.

| | |
|---|---|
| Arquetipo | `business`: Reglas de negocio y propiedad de los datos del dominio |
| Estructura | `hexagonal` |
| Extensiones | `cqrs` |
| Stack | `servlet` |
| Persistencia | `aurora` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.orders` |

## En la demo

Dueño de las órdenes de pago (CQRS sobre Aurora).

- Registrar una orden valida los dígitos de control del CCI del acreedor, asigna la fecha de
  proceso (calendario de negocio y corte a las 18:00), rechaza posibles duplicados, comprueba el
  límite diario por moneda, numera la operación (`OP` + secuencia diaria) y publica
  `payment-order.created.v1` mediante el outbox transaccional, todo en una sola transacción.
- Las cuentas de los acreedores se guardan cifradas (claves de sobre de KMS) con un índice ciego
  para las búsquedas; la API las devuelve enmascaradas.
- payment-process mueve las órdenes por `PROCESSING -> COMPLETED | REJECTED`; cada cambio publica
  `payment-order.status-changed.v1`. La pista de auditoría va a MSK.

## Columnas cifradas

Los contextos AAD de las columnas cifradas son `payment_order.debtor_account` y
`payment_order.creditor_cci`; `payments-history` usa `payment_history.creditor_cci`.

La demo local es desechable: `scripts/demo.sh down` elimina los volúmenes locales y todos los datos
de la demo, y `scripts/demo.sh up` recrea el entorno.

## Requisitos

- JDK 25 (Maven viene con el wrapper `./mvnw`)
- Docker, para `docker compose` y las pruebas con Testcontainers
- Acceso al repositorio de GitHub Packages que publica axiz

## Compilación y pruebas

```bash
./mvnw verify                 # reglas de enforcer, pruebas y reglas de arquitectura
./mvnw spotless:apply         # formatea el código (se ejecuta antes de hacer push)
./mvnw -Pquality verify       # control de CI: además formato, Checkstyle, Error Prone con NullAway, SpotBugs/FindSecBugs,
                              # convergencia de dependencias y umbrales de cobertura
./mvnw -Pnative native:compile  # imagen nativa con GraalVM
```

`ArchitectureTest` hace cumplir las reglas de la estructura hexagonal, de la extensión CQRS y las reglas generales de axiz;
`PaymentOrdersApplicationTest` arranca el servicio contra dependencias reales en contenedores.
`ContractExamplesTest` comprueba que cada ejemplo de petición del contrato sea una petición válida.

## Ejecución local

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, no son credenciales reales
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

También `axiz local up` / `axiz local down`. Las trazas, métricas y logs están en Grafana en
<http://localhost:3000> (colector OpenTelemetry en los puertos 4317/4318).
Keycloak (<http://localhost:8180>, realm `axiz`) emite tokens locales:

```bash
curl -s -d grant_type=client_credentials -d client_id=local-client -d client_secret=local-only \
  http://localhost:8180/realms/axiz/protocol/openid-connect/token
```

## Contrato primero

`src/main/openapi/openapi.yaml` es la fuente de verdad. Los modelos de petición y de respuesta se generan
en tiempo de build en `pe.axiz.payments.orders.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `createPaymentOrder` | POST | `/v1/payment-orders` |
| `listPaymentOrders` | GET | `/v1/payment-orders` |
| `getPaymentOrder` | GET | `/v1/payment-orders/{orderId}` |
| `changePaymentOrderStatus` | POST | `/v1/payment-orders/{orderId}/status` |

## Estructura

- `pe.axiz.payments.orders.domain`: Modelo de dominio: Java simple, sin dependencias de frameworks.
- `pe.axiz.payments.orders.application.command`: Comandos y sus handlers (lado de escritura).
- `pe.axiz.payments.orders.application.query`: Consultas y sus handlers (lado de lectura).
- `pe.axiz.payments.orders.application.port.out`: Puertos de salida implementados por los adaptadores.
- `pe.axiz.payments.orders.adapter.in.rest`: Adaptador REST generado desde el contrato OpenAPI.
- `pe.axiz.payments.orders.adapter.out`: Adaptadores de salida: persistencia, clientes HTTP, mensajería.
- `pe.axiz.payments.orders.config`: Cableado de handlers, puertos y adaptadores.

## Capacidades

| Capacidad | Starter | Qué aporta |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Reloj y calendario de negocio (zona, fines de semana, feriados), valores por defecto de JSON y validación |
| `web` | `axiz-web-spring-boot-starter` | Cabeceras de metadatos de la petición, correlación, paginación, versionado de API, CORS y convenciones JSON |
| `errors` | `axiz-errors-spring-boot-starter` | Detalles de problema RFC 9457, catálogo de errores (ConfigMap `APP_NAME-errors`) y mapeo de errores externos |
| `logging` | `axiz-logging-spring-boot-starter` | Logs JSON estructurados con contexto de traza y de petición, log de acceso HTTP con enmascaramiento |
| `observability` | `axiz-observability-spring-boot-starter` | Trazas y métricas OpenTelemetry mediante Micrometer (OTLP), sin agente Java |
| `metrics` | `axiz-metrics-spring-boot-starter` | Métricas genéricas de eventos (conteo, duración, importes por moneda, valores) con etiquetas acotadas |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps montados como archivos (axiz-common, `APP_NAME`, `APP_NAME-errors`; sin acceso a la API de Kubernetes), Parameter Store y comprobación de claves obligatorias |
| `i18n` | `axiz-i18n-spring-boot-starter` | Mensajes y detalles de problema localizados (es-PE por defecto) desde messages*.properties |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Enmascaramiento de campos sensibles en respuestas, logs y auditoría según el nivel de sensibilidad |
| `security` | `axiz-security-spring-boot-starter` | Servidor de recursos OAuth2 (emisores confiables, audiencias, claims), contexto del llamador y límite de tasa |
| `audit` | `axiz-audit-spring-boot-starter` | Pista de auditoría de negocio con @Auditable, saneada según sensibilidad y publicada de forma asíncrona |
| `jpa` | `axiz-jpa-spring-boot-starter` | Aurora PostgreSQL con JPA, Flyway, AWS JDBC wrapper (IAM, failover, separación de lectura y escritura), auditoría y columnas cifradas |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secretos desde el directorio montado del ExternalSecret, el entorno, Secrets Manager o Parameter Store, con caché |
| `crypto` | `axiz-crypto-spring-boot-starter` | Cifrado de campos (convertidores JPA y DynamoDB, claves rotativas desde el almacén de secretos), cifrado de sobre con KMS, índice ciego, JOSE y hash de contraseñas |
| `outbox` | `axiz-outbox-spring-boot-starter` | Outbox transaccional en Aurora con relay hacia MSK, SNS o SQS (al menos una vez) |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents sobre Amazon MSK con autenticación IAM, reintentos, DLQ y propagación de trazas |
| `sequence` | `axiz-sequence-spring-boot-starter` | Secuencias de negocio (prefijo, ancho, reinicio diario o mensual) sobre JDBC o DynamoDB |
| `audit-kafka` | `axiz-audit-spring-boot-starter` | Eventos de auditoría publicados en MSK |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `payment-orders` y `payment-orders-errors`, que el chart monta como archivos
  (`deploy/helm/payment-orders/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `spring.datasource.url`
- `spring.datasource.username`
- `axiz.jpa.aws-wrapper.enabled`
- `axiz.jpa.aws-wrapper.iam`
- `axiz.crypto.kms.key-id`
- `axiz.crypto.fields.current-key-id`
- `axiz.crypto.fields.key-ids`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.audit.kafka.topic`

## Despliegue

`deploy/helm/payment-orders` contiene el chart: Deployment con probes, Service, HPA, PDB,
ConfigMaps montados como archivos, ExternalSecret, ServiceAccount y una NetworkPolicy. Los pods no
requieren permisos sobre la API de Kubernetes para leer su configuración montada.

Los workflows de `.github/workflows` llaman a los workflows reutilizables de Axiz, fijados al tag
inmutable de su release:

- `ci.yaml`: cada pull request compila con todos los controles de calidad, revisa la compatibilidad
  del contrato OpenAPI, valida el chart y escanea las dependencias. En `main` y en los tags
  publica la imagen (Jib) y el chart en GHCR con atestaciones de procedencia y SBOM; en `main`
  despliega en dev cuando el repositorio define la variable `AXIZ_GITOPS_REPOSITORY`.
- `deploy.yaml`: promueve a un ambiente un digest ya construido y atestiguado; las reglas de
  protección del ambiente de GitHub aportan la aprobación. Volver atrás es desplegar el digest
  anterior; el despliegue nunca recompila.
- `release.yaml`: release-please mantiene un pull request de release con la siguiente versión y el
  changelog; al fusionarlo se crea el tag. `version.txt` y `.release-please-manifest.json` guardan
  la última versión publicada (`0.0.0`: nada publicado todavía); el pom guarda la siguiente
  (`0.1.0-SNAPSHOT`). La primera release es `0.1.0` (`initial-version` en
  `release-please-config.json`); las siguientes siguen Conventional Commits.

Variables opcionales del repositorio: `AXIZ_BASE_IMAGE` (imagen base fijada por digest) y
`AXIZ_NATIVE` (`true` para la imagen nativa). Secretos del despliegue: `GITOPS_APP_CLIENT_ID` y
`GITOPS_APP_PRIVATE_KEY`, de una GitHub App que solo escribe en el repositorio GitOps.

## Decisiones
