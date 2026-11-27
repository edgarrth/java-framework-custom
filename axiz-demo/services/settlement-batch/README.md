# Settlement Batch

Liquidación y conciliación diarias.

| | |
|---|---|
| Arquetipo | `batch`: Procesamiento batch programado |
| Estructura | `batch` |
| Extensiones | Ninguna |
| Stack | `servlet` |
| Persistencia | `aurora` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.settlement` |

## En la demo

CronJob de Kubernetes que carga en Aurora el archivo diario de liquidación de la red de pagos
(ancho fijo, `in/settlement/NET-SETTLE-<yyyyMMdd>.txt`, por defecto el día hábil anterior):

1. `validate`: totales de control del trailer (cantidad e importe) antes de cargar nada.
2. `load`: bloques de 500 líneas, inserción idempotente por id de extremo a extremo y CCI inválidos filtrados.
3. `summary`: totales por moneda y estado como métricas y en `out/settlement/<file>.summary.json`.

Una instancia de job por archivo (un archivo nunca se carga dos veces) y un lock de clúster con
ShedLock para que las ejecuciones manuales y programadas nunca se superpongan. El pod termina con el
código de salida del job.

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

`ArchitectureTest` hace cumplir las reglas del estilo `batch` y las reglas generales de axiz;
`SettlementBatchApplicationTest` arranca el servicio contra dependencias reales en contenedores.

## Ejecución local

```bash
local/dev-secrets.sh       # random development-only secrets in local/secrets (git-ignored)
docker compose up -d
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1   # LocalStack, no son credenciales reales
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

También `axiz local up` / `axiz local down`. Las trazas, métricas y logs están en Grafana en
<http://localhost:3000> (colector OpenTelemetry en los puertos 4317/4318).

## Estructura

- `pe.axiz.payments.settlement.job`: Jobs, pasos, readers, processors y writers.

## Capacidades

| Capacidad | Starter | Qué aporta |
|---|---|---|
| `foundation` | `axiz-foundation-spring-boot-starter` | Reloj y calendario de negocio (zona, fines de semana, feriados), valores por defecto de JSON y validación |
| `errors` | `axiz-errors-spring-boot-starter` | Detalles de problema RFC 9457, catálogo de errores (ConfigMap `APP_NAME-errors`) y mapeo de errores externos |
| `logging` | `axiz-logging-spring-boot-starter` | Logs JSON estructurados con contexto de traza y de petición, log de acceso HTTP con enmascaramiento |
| `observability` | `axiz-observability-spring-boot-starter` | Trazas y métricas OpenTelemetry mediante Micrometer (OTLP), sin agente Java |
| `metrics` | `axiz-metrics-spring-boot-starter` | Métricas genéricas de eventos (conteo, duración, importes por moneda, valores) con etiquetas acotadas |
| `config` | `axiz-config-spring-boot-starter` | ConfigMaps montados como archivos (axiz-common, `APP_NAME`, `APP_NAME-errors`; sin acceso a la API de Kubernetes), Parameter Store y comprobación de claves obligatorias |
| `storage-s3` | `axiz-storage-spring-boot-starter` | Almacenamiento de objetos en S3 (streaming, URL prefirmadas, metadatos), observado |
| `audit` | `axiz-audit-spring-boot-starter` | Pista de auditoría de negocio con @Auditable, saneada según sensibilidad y publicada de forma asíncrona |
| `jpa` | `axiz-jpa-spring-boot-starter` | Aurora PostgreSQL con JPA, Flyway, AWS JDBC wrapper (IAM, failover, separación de lectura y escritura), auditoría y columnas cifradas |
| `scheduling` | `axiz-scheduling-spring-boot-starter` | Jobs @Scheduled con ShedLock (JDBC o DynamoDB) para que solo una réplica los ejecute |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `settlement-batch` y `settlement-batch-errors`, que el chart monta como archivos
  (`deploy/helm/settlement-batch/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.storage.bucket`
- `spring.datasource.url`
- `spring.datasource.username`
- `axiz.jpa.aws-wrapper.enabled`
- `axiz.jpa.aws-wrapper.iam`

## Despliegue

`deploy/helm/settlement-batch` contiene el chart: CronJob,
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
