# Payments UX

API de pagos para los canales.

| | |
|---|---|
| Arquetipo | `ux`: API de canal que compone servicios posteriores |
| Estructura | `bff` |
| Extensiones | Ninguna |
| Stack | `reactive` |
| Persistencia | `none` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.ux` |

## En la demo

API de canal de las apps web y móvil (BFF, reactiva). El cliente siempre es el sujeto del token.

- `POST /v1/payments`: lee el beneficiario directamente de beneficiaries (sin caché), comprueba la
  moneda y el feature flag `payments-usd`, y registra la orden en payment-orders con la
  `Idempotency-Key` del llamador (almacén Redis: una petición reintentada repite la primera respuesta).
- `GET /v1/payments/{id}`: orden (obligatoria) + alias del beneficiario (parte opcional de la
  composición: la pantalla sigue funcionando cuando beneficiaries está lento o caído). El pago de otro
  cliente se informa como no encontrado.
- `GET /v1/beneficiaries`: páginas en caché (`customer-beneficiaries`, 30 s, L1 + Redis), con los
  nombres de los titulares y las cuentas enmascarados para el canal.
- Los errores de negocio de los servicios posteriores se traducen a errores del canal (`PAYMENTS-PAYMENTSUX-*`).
- Clientes HTTP generados con `axiz generate http-client` desde los contratos de `../../contracts`.

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

`ArchitectureTest` hace cumplir las reglas del estilo `bff` y las reglas generales de axiz;
`PaymentsUxApplicationTest` arranca el servicio contra dependencias reales en contenedores.
`ContractExamplesTest` comprueba que cada ejemplo de petición del contrato sea una petición válida.

## Ejecución local

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
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
en tiempo de build en `pe.axiz.payments.ux.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `createPayment` | POST | `/v1/payments` |
| `getPayment` | GET | `/v1/payments/{paymentId}` |
| `listMyBeneficiaries` | GET | `/v1/beneficiaries` |

## Estructura

- `pe.axiz.payments.ux.controller`: API de canal generada desde el contrato OpenAPI.
- `pe.axiz.payments.ux.composition`: Composiciones de llamadas a servicios posteriores, una por tag de la API.
- `pe.axiz.payments.ux.client`: Clientes HTTP de los servicios posteriores.

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
| `resilience` | `axiz-resilience-spring-boot-starter` | Políticas por nombre de timeouts, reintento con backoff, circuit breaker y bulkhead |
| `http-client` | `axiz-http-client-spring-boot-starter` | Clientes HTTP declarativos por nombre con timeouts, reintentos, circuit breaker, client credentials de OAuth2 y propagación de cabeceras |
| `cache` | `axiz-cache-spring-boot-starter` | @AxizCacheable con soporte de Mono/Flux, L1 con Caffeine |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key para POST/PATCH con respuestas guardadas (en memoria solo en local/test; los ambientes compartidos necesitan idempotency-redis o idempotency-dynamodb) |
| `idempotency-redis` | `axiz-idempotency-spring-boot-starter` | Almacén de idempotencia en ElastiCache (Redis/Valkey) |
| `cache-redis` | `axiz-cache-spring-boot-starter` | Caché L2 en ElastiCache (Redis/Valkey), tolerante a caídas de Redis |
| `featureflags` | `axiz-featureflags-spring-boot-starter` | Feature flags (OpenFeature) por porcentaje, canal, tenant y reglas, desde el ConfigMap |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `payments-ux` y `payments-ux-errors`, que el chart monta como archivos
  (`deploy/helm/payments-ux/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `spring.data.redis.url`
- `axiz.feature-flags.flags`

## Despliegue

`deploy/helm/payments-ux` contiene el chart: Deployment con probes, Service, HPA, PDB,
ConfigMaps montados como archivos, ExternalSecret, ServiceAccount y una NetworkPolicy. Los pods no
requieren permisos sobre la API de Kubernetes para leer su configuración montada.
Los pipelines de `.github/workflows` llaman a los workflows compartidos de axiz, fijados a una release inmutable (`@axiz-workflows-vX.Y.Z`; Renovate propone actualizaciones agrupadas hacia el siguiente tag de release inmutable):

- `ci.yaml`: los pull requests ejecutan las comprobaciones compartidas y el build con todos los controles
  de calidad; `main` construye la imagen (Jib, firmada con cosign) y la despliega en dev y luego en qa;
  un tag `v*` despliega en prod tras la aprobación del ambiente `prod`.
- Los despliegues son GitOps: el pipeline registra la release (commit del chart, tag y digest de la imagen)
  en el repositorio GitOps y Argo CD la aplica. `cd.yaml` vuelve a desplegar o revierte una imagen existente.
- `release.yaml`: release-please mantiene un pull request de release con la siguiente versión y el
  changelog; al fusionarlo se crea el tag.

## Decisiones
