# Banking Core

Capa anticorrupción del core bancario (REST y SOAP). Las transferencias corresponden a payment-network-gateway.

| | |
|---|---|
| Arquetipo | `core`: Capa anticorrupción para el core bancario |
| Estructura | `hexagonal` |
| Extensiones | Ninguna |
| Stack | `servlet` |
| Persistencia | `none` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.bankingcore` |

## En la demo

Capa anticorrupción delante del core bancario, simulado con WireMock en local
(contratos en `../../contracts/external`).

- Débitos y reversiones mediante la fachada REST del core (client credentials de OAuth2) y créditos
  mediante su servicio SOAP (WS-Security digest). Los códigos del core (`CB-51`, `CB-62`, etc.) se
  convierten en errores `PAYMENTS-BANKINGCORE-*`; nunca salen de este servicio.
- Los débitos de una misma cuenta se serializan entre réplicas con un lock de Redis.
- Cada movimiento se audita en SQS (lo archiva notifications-processor). Las transferencias de red
  corresponden a payment-network-gateway.

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
`BankingCoreApplicationTest` arranca el servicio contra dependencias reales en contenedores.
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
en tiempo de build en `pe.axiz.payments.bankingcore.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `debitAccount` | POST | `/v1/debits` |
| `reverseDebit` | POST | `/v1/debits/{movementId}/reversal` |
| `creditAccount` | POST | `/v1/credits` |

## Estructura

- `pe.axiz.payments.bankingcore.domain`: Modelo de dominio: Java simple, sin dependencias de frameworks.
- `pe.axiz.payments.bankingcore.application.usecase`: Casos de uso: una clase por operación, transaccionales.
- `pe.axiz.payments.bankingcore.application.port.out`: Puertos de salida implementados por los adaptadores.
- `pe.axiz.payments.bankingcore.adapter.in.rest`: Adaptador REST generado desde el contrato OpenAPI.
- `pe.axiz.payments.bankingcore.adapter.out`: Adaptadores de salida: persistencia, clientes HTTP, mensajería.
- `pe.axiz.payments.bankingcore.config`: Cableado de casos de uso, puertos y adaptadores.

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
| `secrets` | `axiz-secrets-spring-boot-starter` | Secretos desde el directorio montado del ExternalSecret, el entorno, Secrets Manager o Parameter Store, con caché |
| `soap` | `axiz-soap-spring-boot-starter` | Clientes SOAP 1.1/1.2 con WS-Security, timeouts y observaciones |
| `lock` | `axiz-lock-spring-boot-starter` | Locks distribuidos (en memoria solo en local/test; los ambientes compartidos necesitan lock-redis o lock-dynamodb) |
| `lock-redis` | `axiz-lock-spring-boot-starter` | Locks distribuidos en ElastiCache (Redis/Valkey) |
| `audit` | `axiz-audit-spring-boot-starter` | Pista de auditoría de negocio con @Auditable, saneada según sensibilidad y publicada de forma asíncrona |
| `audit-sqs` | `axiz-audit-spring-boot-starter` | Eventos de auditoría publicados en SQS |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `banking-core` y `banking-core-errors`, que el chart monta como archivos
  (`deploy/helm/banking-core/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.http-client.clients`
- `axiz.soap.clients`
- `spring.data.redis.url`
- `axiz.audit.sqs.queue-url`

## Despliegue

`deploy/helm/banking-core` contiene el chart: Deployment con probes, Service, HPA, PDB,
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
