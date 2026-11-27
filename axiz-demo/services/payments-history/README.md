# Payments History

Historial de pagos de cada cliente: modelo de lectura proyectado desde los eventos de las órdenes de pago sobre R2DBC.

| | |
|---|---|
| Arquetipo | `business`: Reglas de negocio y propiedad de los datos del dominio |
| Estructura | `hexagonal` |
| Extensiones | `cqrs` |
| Stack | `reactive` |
| Persistencia | `aurora` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.history` |

## Qué muestra

El lado reactivo de axiz sobre Aurora PostgreSQL, de extremo a extremo y sin bloquear un hilo:

| Pieza | Dónde |
|---|---|
| Consumidores Kafka que alimentan el **bus de comandos reactivo** (`ReactiveTypedEventHandler`) | `adapter/in/messaging` |
| CQRS reactivo: `ReactiveCommandHandler` / `ReactiveQueryHandler`, pipeline con span, validación, **transacción reactiva** (R2DBC) y métricas | `application` |
| Proyección idempotente y tolerante al orden (un estado anterior nunca reemplaza a uno más reciente) | `domain/PaymentHistory` |
| Spring Data R2DBC: `AuditableRow` (UUIDv7, `@Version`, auditoría), columnas `EncryptedText` y `Money` | `adapter/out/persistence` |
| Inbox R2DBC (`axiz.inbox.store: r2dbc`), Flyway sobre JDBC, tokens IAM en AWS (`axiz.r2dbc.iam.enabled`) | `application.yaml`, `db/migration` |
| API WebFlux generada desde el contrato, con el cambio más reciente primero y la cuenta enmascarada | `adapter/in/rest` |

El modelo de lectura es eventualmente consistente con payment-orders: un pago recién pagado puede
mostrar `RECEIVED` o `PROCESSING` durante un momento, y `GET /v1/payments/{id}` responde 404 hasta
que se proyecta su primer evento.

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
`PaymentsHistoryApplicationTest` arranca el servicio contra dependencias reales en contenedores.

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
en tiempo de build en `pe.axiz.payments.history.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `listCustomerPayments` | GET | `/v1/customers/{customerId}/payments` |
| `getPayment` | GET | `/v1/payments/{paymentId}` |

## Estructura

- `pe.axiz.payments.history.domain`: Modelo de dominio: Java simple, sin dependencias de frameworks.
- `pe.axiz.payments.history.application.command`: Comandos y sus handlers (lado de escritura).
- `pe.axiz.payments.history.application.query`: Consultas y sus handlers (lado de lectura).
- `pe.axiz.payments.history.application.port.out`: Puertos de salida implementados por los adaptadores.
- `pe.axiz.payments.history.adapter.in.rest`: Adaptador REST generado desde el contrato OpenAPI.
- `pe.axiz.payments.history.adapter.out`: Adaptadores de salida: persistencia, clientes HTTP, mensajería.
- `pe.axiz.payments.history.config`: Cableado de handlers, puertos y adaptadores.

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
| `r2dbc` | `axiz-r2dbc-spring-boot-starter` | Aurora PostgreSQL para servicios reactivos sobre R2DBC (no bloqueante), Flyway, tokens IAM, auditoría, columnas cifradas y monetarias, endpoint de lectura |
| `inbox` | `axiz-inbox-spring-boot-starter` | Deduplicación en el consumidor (inbox) con leases y retención, sobre JDBC, R2DBC o DynamoDB |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents sobre Amazon MSK con autenticación IAM, reintentos, DLQ y propagación de trazas |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secretos desde el directorio montado del ExternalSecret, el entorno, Secrets Manager o Parameter Store, con caché |
| `crypto` | `axiz-crypto-spring-boot-starter` | Cifrado de campos (convertidores JPA y DynamoDB, claves rotativas desde el almacén de secretos), cifrado de sobre con KMS, índice ciego, JOSE y hash de contraseñas |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `payments-history` y `payments-history-errors`, que el chart monta como archivos
  (`deploy/helm/payments-history/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `spring.r2dbc.url`
- `spring.r2dbc.username`
- `axiz.r2dbc.iam.enabled`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.crypto.kms.key-id`
- `axiz.crypto.fields.current-key-id`
- `axiz.crypto.fields.key-ids`

## Despliegue

`deploy/helm/payments-history` contiene el chart: Deployment con probes, Service, HPA, PDB,
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
