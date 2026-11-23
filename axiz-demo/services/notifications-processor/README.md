# Notifications Processor

Notificaciones a clientes y archivo de auditoría.

| | |
|---|---|
| Arquetipo | `event-processor`: Procesa los eventos suscritos |
| Estructura | `eda` |
| Extensiones | Ninguna |
| Stack | `servlet` |
| Persistencia | `dynamodb` |
| axiz | `2.0.0` |
| Paquete base | `pe.axiz.payments.notifications` |

## En la demo

Procesador de eventos (estilo EDA, DynamoDB).

- `payment-order.status-changed.v1` (Kafka): notifica los pagos COMPLETED y REJECTED por el canal
  del cliente (tabla de contactos) y publica `notification.requested.v1` en SNS.
- `pe.axiz.audit.event.v1` (SQS): archiva la pista de auditoría en S3 (`audit/yyyy/MM/dd/<service>/`)
  con un manifiesto diario actualizado bajo un lock de DynamoDB.
- El inbox (DynamoDB) descarta las reentregas; los fallos van al tópico o cola de mensajes fallidos.

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

`ArchitectureTest` hace cumplir las reglas del estilo `eda` y las reglas generales de axiz;
`NotificationsProcessorApplicationTest` arranca el servicio contra dependencias reales en contenedores.

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

- `pe.axiz.payments.notifications.handler`: Handlers de eventos, uno por tipo de evento.
- `pe.axiz.payments.notifications.event`: Payloads de eventos.

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
| `sensitive` | `axiz-sensitive-spring-boot-starter` | Enmascaramiento de campos sensibles en respuestas, logs y auditoría según el nivel de sensibilidad |
| `messaging-kafka` | `axiz-messaging-spring-boot-starter` | CloudEvents sobre Amazon MSK con autenticación IAM, reintentos, DLQ y propagación de trazas |
| `dynamodb` | `axiz-dynamodb-spring-boot-starter` | Enhanced client de DynamoDB, repositorios con paginación por cursor y bloqueo optimista |
| `messaging-sqs` | `axiz-messaging-spring-boot-starter` | Consumidores y publicadores de CloudEvents sobre SQS (estándar y FIFO) con DLQ |
| `messaging-sns` | `axiz-messaging-spring-boot-starter` | Publicación de CloudEvents en tópicos SNS (difusión) |
| `inbox` | `axiz-inbox-spring-boot-starter` | Deduplicación en el consumidor (inbox) con leases y retención, sobre JDBC o DynamoDB |
| `notification` | `axiz-notification-spring-boot-starter` | Notificaciones con plantillas (email, SMS, push) publicadas como eventos |
| `storage-s3` | `axiz-storage-spring-boot-starter` | Almacenamiento de objetos en S3 (streaming, URL prefirmadas, metadatos), observado |
| `lock` | `axiz-lock-spring-boot-starter` | Locks distribuidos (en memoria solo en local/test; los ambientes compartidos necesitan lock-redis o lock-dynamodb) |
| `lock-dynamodb` | `axiz-lock-spring-boot-starter` | Locks distribuidos en DynamoDB (leases) |

Se agregan más con `axiz add <capability>`: añade el starter, sus propiedades, las claves del ConfigMap
y un ejemplo de uso.

## Configuración

- `application.yaml` contiene solo valores por defecto. Los valores de cada ambiente están en los ConfigMaps `axiz-common`,
  `notifications-processor` y `notifications-processor-errors`, que el chart monta como archivos
  (`deploy/helm/notifications-processor/values-<env>.yaml`, claves `config` y `errors`).
- Los secretos nunca van al repositorio ni a los ConfigMaps: provienen de AWS Secrets Manager mediante
  el ExternalSecret y se montan como archivos en `/var/run/secrets/axiz`.
- El acceso a AWS usa la identidad del pod (IRSA o EKS Pod Identity); no hay claves de AWS en ningún lugar.

Claves que cada ambiente debe definir:

- `axiz.foundation.calendar.holidays`
- `spring.kafka.bootstrap-servers`
- `axiz.messaging.destinations`
- `axiz.dynamodb.table-prefix`
- `axiz.messaging.sqs.queues`
- `axiz.storage.bucket`

## Despliegue

`deploy/helm/notifications-processor` contiene el chart: Deployment con probes, Service, HPA, PDB,
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

## Contactos protegidos y datos locales de arranque

`ContactItem` persiste una dirección `EncryptedValue`. `CustomerContact` es el modelo de entrega
descifrado en memoria. `FieldEncryptor` de Axiz autentica el ID del cliente y el canal junto con cada
dirección; copiar texto cifrado a otro cliente o canal falla. El escritor del perfil de clientes debe
usar el mismo contexto `notification.address` y el binding de cliente y canal con prefijo de longitud
(ver `ContactItem.protect`). Las versiones anteriores de la clave se mantienen disponibles durante la
rotación; el secreto montado es `field-key-v1`.

El perfil `local` activa de forma explícita `demo.local.seed-contacts`. `LocalContactSeed` requiere
un endpoint de AWS en loopback, el ambiente `local` y el prefijo de tabla `local-`. Inserta los dos
contactos ficticios de la demo con cifrado de Axiz y conserva las filas existentes al reiniciar. Los
scripts de infraestructura crean las tablas sin escribir contactos en texto plano. Antes de arrancar
se ejecuta `local/dev-secrets.sh`. Los perfiles de producción no activan estos datos de arranque.

Las filas con direcciones en texto plano se rechazan. Los datos locales desechables se recrean, o se
hace una migración autorizada con la clave de campo correspondiente antes de usar los datos
persistidos.
