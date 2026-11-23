# Beneficiaries

Beneficiarios frecuentes de cada cliente.

| | |
|---|---|
| Arquetipo | `business`: Reglas de negocio y propiedad de los datos del dominio |
| Estructura | `layered` |
| Extensiones | Ninguna |
| Stack | `servlet` |
| Persistencia | `dynamodb` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.beneficiaries` |

## En la demo

Beneficiarios frecuentes de cada cliente (estilo layered sobre DynamoDB).

- Un beneficiario activo por cuenta y cliente, como máximo `beneficiaries.max-per-customer`.
- Borrado lógico (`deletedAt`): los ítems se conservan para auditoría y las consultas los omiten.
- Paginación por cursor sobre la partición de DynamoDB; se rechaza el cursor de otro cliente.
- Las lecturas fuertemente consistentes ven los cambios hechos por otras réplicas; las decisiones de
  autorización y revocación no dependen de una caché local. El registro es idempotente con la
  `Idempotency-Key` (almacén DynamoDB compartido por las réplicas).
- El registro comprueba el conjunto activo y confirma la fila nueva junto con una revisión del
  directorio por cliente en una sola transacción condicional de DynamoDB. Los intentos concurrentes
  no pueden superar el límite del cliente ni crear dos entradas activas para el mismo CCI. Las
  actualizaciones y los borrados también avanzan esa revisión. El CCI permanece cifrado; las
  comprobaciones de unicidad no introducen un hash sin clave.
- La política IAM de runtime necesita `dynamodb:TransactWriteItems` sobre la tabla de beneficiarios
  además de sus permisos de consulta y lectura. La fila de control `!directory` comparte esa tabla y
  se excluye de los resultados. Todos los escritores de la tabla deben avanzar la revisión del
  directorio: un escritor que no la avance invalidaría la garantía de concurrencia.

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

`ArchitectureTest` hace cumplir las reglas del estilo `layered` y las reglas generales de axiz;
`BeneficiariesApplicationTest` arranca el servicio contra dependencias reales en contenedores.
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
en tiempo de build en `pe.axiz.payments.beneficiaries.api.model` (nunca se versionan). Los controladores se generan una sola vez
como esqueletos que responden `501 Not Implemented` hasta que se implementa cada operación.

- `axiz contract sync` agrega esqueletos para las operaciones nuevas, informa las eliminadas y comprueba
  los cambios incompatibles contra la versión anterior (openapi-diff).
- El contrato se valida con el ruleset de Spectral de axiz antes de publicarlo.

| Operación | Método | Ruta |
|---|---|---|
| `listBeneficiaries` | GET | `/v1/customers/{customerId}/beneficiaries` |
| `createBeneficiary` | POST | `/v1/customers/{customerId}/beneficiaries` |
| `getBeneficiary` | GET | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |
| `updateBeneficiary` | PUT | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |
| `deleteBeneficiary` | DELETE | `/v1/customers/{customerId}/beneficiaries/{beneficiaryId}` |

## Estructura

- `pe.axiz.payments.beneficiaries.controller`: Controladores REST generados desde el contrato OpenAPI.
- `pe.axiz.payments.beneficiaries.service`: Servicios de negocio, transaccionales.
- `pe.axiz.payments.beneficiaries.repository`: Acceso a datos.
- `pe.axiz.payments.beneficiaries.model`: Entidades e ítems.

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
| `dynamodb` | `axiz-dynamodb-spring-boot-starter` | Enhanced client de DynamoDB, repositorios con paginación por cursor y bloqueo optimista |
| `idempotency` | `axiz-idempotency-spring-boot-starter` | Idempotency-Key para POST/PATCH con respuestas guardadas (en memoria solo en local/test; los ambientes compartidos necesitan idempotency-redis o idempotency-dynamodb) |
| `idempotency-dynamodb` | `axiz-idempotency-dynamodb-spring-boot-starter` | Almacén de idempotencia en DynamoDB (escrituras condicionales y TTL) |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `beneficiaries` y `beneficiaries-errors`, que el chart monta como archivos
  (`deploy/helm/beneficiaries/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `axiz.security.issuers`
- `axiz.dynamodb.table-prefix`

## Despliegue

`deploy/helm/beneficiaries` contiene el chart: Deployment con probes, Service, HPA, PDB,
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

## Almacenamiento protegido de beneficiarios

El repositorio cifra el CCI con `FieldEncryptor` de Axiz antes de escribir en DynamoDB. El mapeo de
persistencia `BeneficiaryItem` contiene `EncryptedValue`; los controladores usan el modelo de negocio
separado. Los datos autenticados ligan el texto cifrado al cliente y al ID del beneficiario, de modo
que mover texto cifrado entre filas hace fallar la autenticación. El secreto de archivo
`field-key-v1` lo genera `local/dev-secrets.sh`; los ambientes desplegados lo montan mediante
External Secrets. Las versiones anteriores de la clave se conservan mientras haya filas que las
referencien.

No existe alternativa en texto plano. Las filas locales sin cifrar se eliminan y se recrean, o se
migran mediante un proceso autorizado de forma explícita que cifra cada CCI con el binding de su
fila. Cambiar la clave sin migrar las filas impide descifrarlas.
