# Payment Network Gateway

Fachada y seguridad para sistemas externos.

| | |
|---|---|
| Arquetipo | `gateway`: Fachada de la red de pagos externa |
| Estructura | `hexagonal` |
| Extensiones | Ninguna |
| Stack | `servlet` |
| Persistencia | `none` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.networkgateway` |

## En la demo

`POST /v1/transfers` acepta peticiones de payment-process con la autoridad `payments:process`.
El caso de uso valida el CCI y llama a la red de pagos mediante su propio puerto de salida. La API
key se lee desde archivos de secretos montados. Redis guarda las respuestas idempotentes, la clave
del llamador se reenvía a la red y la transferencia se audita en SQS. El perfil de plataforma usa el
puerto 8087. Los débitos, créditos y reversiones corresponden a banking-core.

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

`ArchitectureTest` hace cumplir las reglas del estilo `hexagonal` y las reglas generales de axiz;
`PaymentNetworkGatewayApplicationTest` arranca el servicio contra dependencias reales en contenedores.
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
en tiempo de build en `pe.axiz.payments.networkgateway.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `sendTransfer` | POST | `/v1/transfers` |

## Estructura

- `pe.axiz.payments.networkgateway.domain`: Modelo de dominio: Java simple, sin dependencias de la estructura.
- `pe.axiz.payments.networkgateway.application.usecase`: Casos de uso: una clase por operación, transaccionales.
- `pe.axiz.payments.networkgateway.application.model`: Contratos de aplicación propios, independientes de los modelos HTTP.
- `pe.axiz.payments.networkgateway.application.port.out`: Puertos de salida implementados por los adaptadores.
- `pe.axiz.payments.networkgateway.adapter.in.rest`: Adaptador REST generado desde el contrato OpenAPI.
- `pe.axiz.payments.networkgateway.adapter.out`: Adaptadores de salida: persistencia, clientes HTTP, mensajería.
- `pe.axiz.payments.networkgateway.config`: Cableado de casos de uso, puertos y adaptadores.

## Capacidades

| Capacidad | Starter | Qué aporta |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Reloj y calendario de negocio (zona, fines de semana, feriados), valores por defecto de JSON y validación |
| `errors` | `axiz-errors-spring-boot-starter` | Detalles de problema RFC 9457, catálogo de errores (ConfigMap `APP_NAME-errors`) y mapeo de errores externos |
| `logging` | `axiz-logging-spring-boot-starter` | Logs JSON estructurados con contexto de traza y de petición, log de acceso HTTP con enmascaramiento |
| `observability` | `axiz-observability-spring-boot-starter` | Trazas y métricas OpenTelemetry mediante Micrometer (OTLP), sin agente Java |
| `metrics` | `axiz-metrics-spring-boot-starter` | Métricas genéricas de eventos (conteo, duración, importes por moneda, valores) con etiquetas acotadas |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps montados como archivos (axiz-common, `APP_NAME`, `APP_NAME-errors`; sin acceso a la API de Kubernetes), Parameter Store y comprobación de claves obligatorias |
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Enmascaramiento de campos sensibles en respuestas, logs y auditoría según el nivel de sensibilidad |
| `web` | `axiz-web-spring-boot-starter` | Cabeceras de metadatos de la petición, correlación, paginación, versionado de API, CORS y convenciones JSON |
| `security` | `axiz-security-spring-boot-starter` | Servidor de recursos OAuth2 (emisores confiables, audiencias, claims), contexto del llamador y límite de tasa |
| `resilience` | `axiz-resilience-spring-boot-starter` | Políticas por nombre de timeouts, reintento con backoff, circuit breaker y bulkhead |
| `http-client` | `axiz-http-client-spring-boot-starter` | Clientes HTTP declarativos por nombre con timeouts, reintentos, circuit breaker, client credentials de OAuth2 y propagación de cabeceras |
| `secrets` | `axiz-secrets-spring-boot-starter` | Secretos desde el directorio montado del ExternalSecret, el entorno, Secrets Manager o Parameter Store, con caché |
| `audit` | `axiz-audit-spring-boot-starter` | Pista de auditoría de negocio con @Auditable, saneada según sensibilidad y publicada de forma asíncrona |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key para POST/PATCH con respuestas guardadas (en memoria solo en local/test; los ambientes compartidos necesitan idempotency-redis o idempotency-dynamodb) |
| `idempotency-redis` | `axiz-idempotency-spring-boot-starter` | Almacén de idempotencia en ElastiCache (Redis/Valkey) |
| `audit-sqs` | `axiz-audit-spring-boot-starter` | Eventos de auditoría publicados en SQS |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `payment-network-gateway` y `payment-network-gateway-errors`, que el chart monta como archivos
  (`deploy/helm/payment-network-gateway/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `spring.data.redis.url`
- `axiz.audit.sqs.queue-url`

## Despliegue

`deploy/helm/payment-network-gateway` contiene el chart: Deployment con probes, Service, HPA, PDB,
ConfigMaps montados como archivos, ExternalSecret, ServiceAccount y una NetworkPolicy. Los pods no
requieren permisos sobre la API de Kubernetes para leer su configuración montada.
Los workflows usan los contratos inmutables de Axiz 2.0.0:

- `ci.yaml` compila y valida el producto. Registra el commit de origen, la versión final, la variante
  JVM o nativa, las plataformas y la imagen base fijada en un manifiesto de build firmado.
  `AXIZ_JVM_BASE_IMAGE` y `AXIZ_NATIVE_BASE_IMAGE` se configuran con referencias de registro con digest SHA-256;
  un build manual puede entregar un `base-image` específico. Las entradas opcionales de candidato del
  framework ligan la dependencia a la ejecución que la produjo, su intento y el hash de su manifiesto firmado.
- `deploy.yaml` también escucha las ejecuciones completadas de `ci` y promueve a dev un build verificado
  del servicio mediante una prueba firmada del productor. Exige que el origen sea el HEAD vigente de la
  rama main protegida y un secreto de repositorio `GITOPS_READ_TOKEN` con acceso de solo lectura a GitOps.
  El resolvedor lee el predecesor sin usar la credencial de escritura de la GitHub App; el despliegue
  independiente vuelve a comprobar ese predecesor antes de escribir. Las ejecuciones de CI sin prueba de
  producto no despliegan.
- `deploy.yaml` promueve o revierte un build completado con éxito. Recibe su run id, el intento, el
  SHA-256 del manifiesto, el ambiente, el commit completo de values y el hash del manifiesto desplegado
  anterior (`initial-deployment` para el primer despliegue). Reutiliza los digests de imagen y de chart,
  actualiza GitOps y espera a Argo CD; el despliegue nunca recompila. Los ambientes protegidos aportan la aprobación.
- La compilación usa `GITHUB_TOKEN` con `packages: read`; `PACKAGES_READ_USERNAME` y
  `PACKAGES_READ_TOKEN`, opcionales, permiten leer dependencias de otro repositorio. Un publicador aislado
  verifica el worker y sube los bytes originales OCI y del chart con `packages: write`.
  Los pull requests compilan y escanean sin publicar ni firmar. El despliegue lee GHCR con su
  `GITHUB_TOKEN` y usa la GitOps App y el token de lectura de Argo. Se usa el mismo `BUILD_DESTINATION`
  para el build, la resolución automática y el despliegue; `ARGOCD_SERVER` se configura como variable del repositorio.
- `release.yaml`: release-please mantiene un pull request de release con la siguiente versión y el
  changelog; al fusionarlo se crea el tag. `version.txt` y `.release-please-manifest.json` guardan
  la última versión publicada (`0.0.0`: nada publicado todavía); el pom guarda la siguiente
  (`2.0.0`). La primera release es `2.0.0` (`initial-version` en
  `release-please-config.json`); las siguientes siguen Conventional Commits.

## Decisiones
