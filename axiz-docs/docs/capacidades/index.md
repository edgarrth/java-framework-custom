<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# Capacidades

Catálogo de lo que `axiz add <capacidad>` (y el initializr) agrega a un servicio: starter, propiedades, llaves de ConfigMap, secretos, tablas y un ejemplo. Las capacidades marcadas en un arquetipo vienen incluidas al crear el servicio.

| Capacidad | Qué aporta | Incluida en |
|---|---|---|
| [`foundation`](foundation.md) | Reloj y calendario de negocio (zona, fines de semana, feriados), valores por defecto de JSON y validación | business, core, gateway, ux, support, event-processor, batch |
| [`web`](web.md) | Cabeceras de metadatos de la petición, correlación, paginación, versionado de API, CORS y convenciones JSON | business, core, gateway, ux, support |
| [`errors`](errors.md) | Problem details RFC 9457, catálogo de errores (ConfigMap &lt;app&gt;-errors) y mapeo de errores externos | business, core, gateway, ux, support, event-processor, batch |
| [`logging`](logging.md) | Logs JSON estructurados con traza y contexto de la petición, log de acceso HTTP con enmascaramiento | business, core, gateway, ux, support, event-processor, batch |
| [`observability`](observability.md) | Trazas y métricas OpenTelemetry mediante Micrometer (OTLP), sin agente Java | business, core, gateway, ux, support, event-processor, batch |
| [`metrics`](metrics.md) | Métricas genéricas de eventos (conteo, duración, importes por moneda, valores) con tags acotados | business, core, gateway, ux, support, event-processor, batch |
| [`config`](config.md) | ConfigMaps montados como archivos (axiz-common, &lt;app&gt;, &lt;app&gt;-errors; sin acceso a la API de Kubernetes) y comprobación de llaves requeridas | business, core, gateway, ux, support, event-processor, batch |
| [`config-aws`](config-aws.md) | Arranque opcional desde AWS Parameter Store con ambiente y destino elegidos por el proceso | ninguno |
| [`i18n`](i18n.md) | Mensajes y problem details localizados (es-PE por defecto) desde messages*.properties | business, core, ux, support |
| [`sensitive`](sensitive.md) | Enmascaramiento de campos sensibles en respuestas, logs y auditoría según el nivel de sensibilidad | business, core, gateway, ux, support, event-processor, batch |
| [`security`](security.md) | Resource server OAuth2 (emisores confiables, audiencias, claims), contexto del llamador y límite de tasa | business, core, gateway, ux, support, event-processor |
| [`security-redis`](security-redis.md) | Límites de tasa compartidos en Redis con el transporte TLS gestionado | ninguno |
| [`http-client`](http-client.md) | Clientes HTTP declarativos por nombre con timeouts, reintentos, circuit breaker, OAuth2 client credentials y propagación de cabeceras | core, gateway, ux |
| [`resilience`](resilience.md) | Políticas de timeout, reintento con backoff, circuit breaker y bulkhead por nombre | core, gateway, ux |
| [`soap`](soap.md) | Clientes SOAP 1.1/1.2 con WS-Security, timeouts y observaciones | ninguno |
| [`secrets`](secrets.md) | Fuentes de secretos ordenadas y caché con TTL acotado, independientes del proveedor | business, core, gateway, batch |
| [`secrets-file`](secrets-file.md) | Archivos de secretos montados y variables de entorno, con instantáneas nativas refrescadas | ninguno |
| [`secrets-aws`](secrets-aws.md) | Fuentes explícitas de Secrets Manager y Parameter Store con identidad de AWS compartida | ninguno |
| [`jpa`](jpa.md) | PostgreSQL con JPA, Flyway, auditoría y columnas cifradas | ninguno |
| [`jpa-aws`](jpa-aws.md) | AWS JDBC wrapper para failover de Aurora y autenticación IAM opcional | ninguno |
| [`r2dbc`](r2dbc.md) | Aurora PostgreSQL para servicios reactivos sobre R2DBC (no bloqueante), Flyway, auditoría, columnas cifradas y de dinero, endpoint de lectura | ninguno |
| [`r2dbc-aws`](r2dbc-aws.md) | Autenticación IAM de AWS con credenciales independientes de escritura y de lectura | ninguno |
| [`dynamodb`](dynamodb.md) | Cliente enhanced de DynamoDB, repositorios con paginación por cursor y bloqueo optimista | ninguno |
| [`dynamodb-cursor`](dynamodb-cursor.md) | Cursores de DynamoDB autenticados y ligados a la consulta, con claves rotativas compartidas | ninguno |
| [`cache`](cache.md) | @AxizCacheable con soporte de Mono/Flux y Caffeine como L1 | ux |
| [`cache-redis`](cache-redis.md) | Caché L2 en ElastiCache (Redis/Valkey), tolerante a caídas de Redis | ninguno |
| [`storage`](storage.md) | Puertos de almacenamiento de objetos síncronos y asíncronos nativos, y fachadas de Spring | batch |
| [`messaging`](messaging.md) | Puertos, handlers y rutas CloudEvents, y fachada de Spring | event-processor |
| [`storage-s3`](storage-s3.md) | Almacenamiento de objetos en S3 (streaming, URLs prefirmadas, metadatos), con observaciones | batch |
| [`sequence`](sequence.md) | Secuencias de negocio (prefijo, ancho, reinicio diario o mensual) sobre JDBC, R2DBC nativo o DynamoDB | ninguno |
| [`messaging-kafka`](messaging-kafka.md) | CloudEvents sobre Kafka con reintentos, DLQ y propagación de trazas | event-processor |
| [`messaging-msk`](messaging-msk.md) | Autenticación IAM de MSK mediante la política compartida de credenciales de AWS | ninguno |
| [`messaging-sqs`](messaging-sqs.md) | Consumidores y publicadores CloudEvents sobre SQS (estándar y FIFO) con DLQ | ninguno |
| [`messaging-sns`](messaging-sns.md) | Publicación CloudEvents en tópicos SNS (difusión) | ninguno |
| [`outbox`](outbox.md) | Outbox transaccional en Aurora (JDBC o R2DBC) con relay hacia MSK, SNS o SQS (al menos una vez) | ninguno |
| [`inbox`](inbox.md) | Deduplicación del consumidor (inbox) con leases y retención, sobre JDBC, R2DBC o DynamoDB | event-processor |
| [`idempotency`](idempotency.md) | Idempotency-Key para POST/PATCH con respuestas guardadas (en memoria solo en local/test; los ambientes compartidos requieren idempotency-redis o idempotency-dynamodb) | gateway |
| [`idempotency-redis`](idempotency-redis.md) | Almacén de idempotencia en ElastiCache (Redis/Valkey) | ninguno |
| [`idempotency-dynamodb`](idempotency-dynamodb.md) | Almacén de idempotencia en DynamoDB (escrituras condicionales y TTL) | ninguno |
| [`saga`](saga.md) | Sagas orquestadas con estado persistido, reintentos por paso y compensaciones | ninguno |
| [`lock`](lock.md) | Locks distribuidos (en memoria solo en local/test; los ambientes compartidos requieren lock-redis o lock-dynamodb) | ninguno |
| [`lock-redis`](lock-redis.md) | Locks distribuidos en ElastiCache (Redis/Valkey) | ninguno |
| [`lock-dynamodb`](lock-dynamodb.md) | Locks distribuidos en DynamoDB (leases) | ninguno |
| [`scheduling`](scheduling.md) | Jobs @Scheduled con ShedLock (JDBC, R2DBC o DynamoDB) para que solo una réplica los ejecute | ninguno |
| [`audit`](audit.md) | Rastro de auditoría de negocio con @Auditable, saneado por sensibilidad, con entrega ASYNC/DURABLE/SYNC explícita y cadena HMAC con clave fuera de local/test/ci | business, core, gateway, batch |
| [`audit-kafka`](audit-kafka.md) | Eventos de auditoría publicados en MSK | ninguno |
| [`audit-sqs`](audit-sqs.md) | Eventos de auditoría publicados en SQS | ninguno |
| [`crypto`](crypto.md) | Cifrado de campos, índices ciegos con rotación independiente y hash de contraseñas | ninguno |
| [`crypto-kms`](crypto-kms.md) | Cifrado de sobre con AWS KMS y reutilización acotada de claves de datos | ninguno |
| [`carddata`](carddata.md) | Tipos semánticos de PAN y de datos de autenticación transitorios; enmascarados por defecto, sin bóveda | ninguno |
| [`tokenization`](tokenization.md) | Cliente remoto de tokens autenticado, sin almacenamiento de bóveda, claves ni SDK de proveedores | ninguno |
| [`tokenization-vault`](tokenization-vault.md) | Bóveda central de tokens con políticas explícitas, auditoría durable y presupuestos distribuidos | ninguno |
| [`tokenization-vault-dynamodb`](tokenization-vault-dynamodb.md) | Tabla explícita de la bóveda central, unicidad atómica y controles de rotación de claves | ninguno |
| [`tokenization-vault-redis`](tokenization-vault-redis.md) | Conexión TLS autenticada y dedicada para presupuestos de tokens distribuidos que fallan de forma cerrada | ninguno |
| [`tokenization-pan`](tokenization-pan.md) | Perfil PAN para la bóveda genérica; independiente de los despliegues solo para DNI | ninguno |
| [`tenancy`](tenancy.md) | Multi-tenancy desde el token o una cabecera confiable, propagado a logs, métricas y datos | ninguno |
| [`featureflags`](featureflags.md) | Feature flags (OpenFeature) por porcentaje, canal, tenant y reglas, desde el ConfigMap | ninguno |
| [`notification`](notification.md) | Notificaciones con plantilla (correo, SMS, push) publicadas como eventos | ninguno |
