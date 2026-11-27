<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# Referencia de módulos

Tipos públicos principales de cada módulo, generados desde los README del framework.

## Plataforma: parents y BOMs

| Módulo | Descripción |
|---|---|
| [`axiz-bom`](platform/axiz-bom.md) | Lista de materiales (BOM) de los artefactos de axiz: todos los módulos `pe.axiz` con la misma versión. |
| [`axiz-dependencies`](platform/axiz-dependencies.md) | Versiones de todas las librerías de terceros que usa la plataforma (trenes de Spring Boot y Spring |
| [`axiz-parent-api`](platform/axiz-parent-api.md) | APIs neutrales de estructuras y extensiones: contratos del núcleo más el estándar de interoperabilidad Reactive Streams; sin Reactor ni runtime. |
| [`axiz-parent-core`](platform/axiz-parent-core.md) | Parent de los módulos de la capa 1 y de las librerías Java simples de los equipos: Java 25, los |
| [`axiz-parent-service`](platform/axiz-parent-service.md) | Parent de todos los microservicios: repackage de Spring Boot, imagen Jib (sin root, etiqueta inmutable; |
| [`axiz-parent-spring`](platform/axiz-parent-spring.md) | Parent de los starters de la capa 2, de los frameworks de la capa 3 y de las librerías Spring de los |
| [`axiz-parent-technology`](platform/axiz-parent-technology.md) | Parent Maven de los adaptadores de tecnología y de los kits de persistencia. Hereda los estándares y las pruebas compartidas y prohíbe el acoplamiento con frameworks de runtime. Gobierna las dependencias de tecnología con independencia de los contratos puros del núcleo. |
| [`axiz-parent-test`](platform/axiz-parent-test.md) | Librerías de soporte de pruebas: fixtures y suites de contrato. Este parent es solo para módulos de calidad y nunca es un contrato de runtime. |
| [`axiz-parent-tooling`](platform/axiz-parent-tooling.md) | Parent de las herramientas de build, los recursos de arquetipos y los generadores de código. Estos |
| [`axiz-schema`](platform/axiz-schema.md) | Recursos SQL canónicos de las tablas de Axiz, bajo `axiz/sql/`. Este JAR contiene solo recursos: sin drivers, runtime ni ejecutor de migraciones al arrancar. Los adaptadores JDBC y la generación de proyectos consumen los mismos bytes. |

## Capa 1: núcleo y puertos sin I/O

| Módulo | Descripción |
|---|---|
| [`axiz-core-api`](core/axiz-core-api.md) | Respuestas de API, paginación y contratos de cabeceras. |
| [`axiz-core-audit`](core/axiz-core-audit.md) | Eventos de auditoría inmutables, políticas de publicación, saneamiento y publicadores asíncronos. |
| [`axiz-core-cache`](core/axiz-core-cache.md) | Puerto de caché, caché de dos niveles e implementación en memoria. |
| [`axiz-core-carddata`](core/axiz-core-carddata.md) | Tipos de valor para datos de tarjeta con revelación controlada y representaciones enmascaradas. |
| [`axiz-core-commons`](core/axiz-core-commons.md) | Contratos operativos neutrales respecto del framework: límites y resultados de transacciones, ambientes de despliegue, políticas de seguridad del transporte y marcas de código generado y de estado revisado. |
| [`axiz-core-concurrent`](core/axiz-core-concurrent.md) | Concurrencia acotada y composición asíncrona. |
| [`axiz-core-context`](core/axiz-core-context.md) | Metadatos de petición inmutables y ámbitos de contexto explícitos. |
| [`axiz-core-crypto`](core/axiz-core-crypto.md) | AES-256-GCM, cifrado de sobre, RSA-OAEP, HMAC, hashes, hash de contraseñas, índice ciego y rotación de claves. |
| [`axiz-core-domain`](core/axiz-core-domain.md) | Bloques de construcción DDD: raíz de agregado, entidad, objeto de valor, eventos de dominio, identificadores tipados y objetos de valor comunes. |
| [`axiz-core-errors`](core/axiz-core-errors.md) | Modelo de errores: tipos, categorías con su mapeo HTTP, AxizException, problema RFC 9457 y catálogo externalizado. |
| [`axiz-core-featureflags`](core/axiz-core-featureflags.md) | Feature flags segmentados por canal, tenant o porcentaje, con proveedor compatible con OpenFeature. |
| [`axiz-core-fileformats`](core/axiz-core-fileformats.md) | Registros CSV y de ancho fijo, conversión EBCDIC, checksums y trailers de control. |
| [`axiz-core-http`](core/axiz-core-http.md) | Modelo de cliente HTTP: configuración por cliente, autenticación saliente, reglas de reintento, traducción de errores y propagación de cabeceras. |
| [`axiz-core-i18n`](core/axiz-core-i18n.md) | Resolución de mensajes desde varios paquetes con respaldo de locale. |
| [`axiz-core-id`](core/axiz-core-id.md) | UUIDv7 y ULID ordenados por tiempo, identificadores de correlación y de idempotencia. |
| [`axiz-core-idempotency`](core/axiz-core-idempotency.md) | Un resolvedor autorizado que confirma que no hubo efecto libera la clave y responde 409 |
| [`axiz-core-identifiers`](core/axiz-core-identifiers.md) | SPI genérica de identificadores con validadores (documentos nacionales, cuentas bancarias, tarjetas y datos de contacto). |
| [`axiz-core-inbox`](core/axiz-core-inbox.md) | Contratos y orquestación del consumidor idempotente; el almacenamiento pertenece a los adaptadores de proveedor. |
| [`axiz-core-json`](core/axiz-core-json.md) | Lectura y serialización JSON estrictas y acotadas. |
| [`axiz-core-lang`](core/axiz-core-lang.md) | Utilidades de lenguaje en Java puro: `Checks`, `Strings`, `Dates`, `ValueSnapshots` y `Result` (con `Ok` y `Err`). |
| [`axiz-core-lock`](core/axiz-core-lock.md) | Puerto de lock distribuido con lease, renovación en segundo plano y plantilla de lock; implementación en memoria. |
| [`axiz-core-logging`](core/axiz-core-logging.md) | Campos de log estructurados, MDC por ámbito, registro de operaciones y lista de cabeceras permitidas. |
| [`axiz-core-messaging`](core/axiz-core-messaging.md) | EventPublisher y EventHandler sobre CloudEvents 1.0, cabeceras de negocio y políticas de reintento y DLQ. |
| [`axiz-core-metrics`](core/axiz-core-metrics.md) | Métricas genéricas de eventos: siete instrumentos parametrizados por el tag del evento con protección de cardinalidad. |
| [`axiz-core-money`](core/axiz-core-money.md) | Objeto de valor de dinero con monedas ISO 4217, redondeo y aritmética segura; puerto de tipo de cambio. |
| [`axiz-core-notification`](core/axiz-core-notification.md) | Solicitudes de notificación (correo, SMS, push) con plantillas, publicadas como eventos. |
| [`axiz-core-observability`](core/axiz-core-observability.md) | Convenciones de observación, atributos estándar y utilidades para los spans del framework y los eventos estructurados. |
| [`axiz-core-outbox`](core/axiz-core-outbox.md) | El relay usa un plazo monotónico tomado antes de obtener su conexión de reclamo. Inicia un envío |
| [`axiz-core-resilience`](core/axiz-core-resilience.md) | Políticas de reintento con backoff y jitter, decisiones de reintento que respetan la idempotencia y timeouts. |
| [`axiz-core-saga`](core/axiz-core-saga.md) | Sagas orquestadas con reintentos, compensación y estado persistente y reanudable. |
| [`axiz-core-secrets`](core/axiz-core-secrets.md) | Puerto SecretResolver con resolvedores de entorno y de archivos y caché con TTL. |
| [`axiz-core-security`](core/axiz-core-security.md) | AxizPrincipal, mapeo de claims, modelo de autoridades, claves de límite de tasa y firmas de peticiones. |
| [`axiz-core-sensitive`](core/axiz-core-sensitive.md) | Niveles de sensibilidad, estrategias de enmascaramiento y anotaciones para respuestas, logs y auditoría. |
| [`axiz-core-sequence`](core/axiz-core-sequence.md) | Secuencias atómicas con formato, con puertos síncrono y asíncrono nativo. PostgreSQL usa una tabla |
| [`axiz-core-soap`](core/axiz-core-soap.md) | Soporte ligero para clientes SOAP 1.1 y 1.2: sobres, WS-Security UsernameToken y mapeo de faults. |
| [`axiz-core-storage`](core/axiz-core-storage.md) | Puerto de almacenamiento de objetos con streaming, URLs prefirmadas y metadatos; implementación en memoria. |
| [`axiz-core-tenancy`](core/axiz-core-tenancy.md) | Contexto, resolución y propagación del tenant. |
| [`axiz-core-time`](core/axiz-core-time.md) | Reloj inyectable, calendario de negocio, feriados, horas de corte y fechas de vencimiento. |
| [`axiz-core-tokenization`](core/axiz-core-tokenization.md) | Contratos de tokenización tipados y neutrales respecto del proveedor, con valores desechables enmascarados. |
| [`axiz-core-tokenization-pan`](core/axiz-core-tokenization-pan.md) | Perfil PAN y fachada tipada sobre el contrato genérico de tokenización. |
| [`axiz-core-tokenization-vault`](core/axiz-core-tokenization-vault.md) | Orquestación de la bóveda de tokens con principal explícito y puertos de persistencia atómica. |
| [`core`](core/core.md) | Contratos y algoritmos `axiz-core-<capacidad>`, independientes de Spring y de los clientes de |

## Capa 2: adaptadores tecnológicos

| Módulo | Descripción |
|---|---|
| [`adapters`](adapters/adapters.md) | Los adaptadores tecnológicos implementan los puertos de core o los contratos tecnológicos documentados. Los servicios los seleccionan en runtime; su código importa puertos y kits de persistencia, no las implementaciones de los adaptadores. |
| [`axiz-adapter-audit-kafka`](adapters/axiz-adapter-audit-kafka.md) | Publicador de auditoría para Amazon MSK (Kafka). |
| [`axiz-adapter-audit-sqs`](adapters/axiz-adapter-audit-sqs.md) | Publicador de auditoría para Amazon SQS. |
| [`axiz-adapter-aws`](adapters/axiz-adapter-aws.md) | Infraestructura de identidad y transporte de AWS sin dependencias de Micrometer ni de Spring. |
| [`axiz-adapter-cache-redis`](adapters/axiz-adapter-cache-redis.md) | Caché en Redis (ElastiCache/Valkey) con desalojo basado en SCAN y tolerancia a fallos. |
| [`axiz-adapter-crypto-jose`](adapters/axiz-adapter-crypto-jose.md) | Utilidades JWS y JWE. |
| [`axiz-adapter-crypto-kms`](adapters/axiz-adapter-crypto-kms.md) | Proveedor de claves de AWS KMS para cifrado de sobre con caché de claves de datos. |
| [`axiz-adapter-database-auth-aws`](adapters/axiz-adapter-database-auth-aws.md) | Tokens de contraseña IAM de AWS RDS/Aurora que implementan `DatabasePasswordProvider` de |
| [`axiz-adapter-http-jdk`](adapters/axiz-adapter-http-jdk.md) | Transportes HTTP del JDK para OAuth y SOAP. |
| [`axiz-adapter-idempotency-dynamodb`](adapters/axiz-adapter-idempotency-dynamodb.md) | Almacén de idempotencia en DynamoDB con TTL. |
| [`axiz-adapter-idempotency-redis`](adapters/axiz-adapter-idempotency-redis.md) | Almacén de idempotencia en Redis (ElastiCache). |
| [`axiz-adapter-inbox-dynamodb`](adapters/axiz-adapter-inbox-dynamodb.md) | Almacén de inbox en DynamoDB con TTL. |
| [`axiz-adapter-inbox-jdbc`](adapters/axiz-adapter-inbox-jdbc.md) | Persistencia del inbox y recursos de esquema sobre JDBC. |
| [`axiz-adapter-inbox-r2dbc`](adapters/axiz-adapter-inbox-r2dbc.md) | Inbox (consumidor idempotente) en Aurora PostgreSQL sobre R2DBC. |
| [`axiz-adapter-kafka-msk`](adapters/axiz-adapter-kafka-msk.md) | Autenticación IAM de Amazon MSK con credenciales de AWS propias. |
| [`axiz-adapter-lock-dynamodb`](adapters/axiz-adapter-lock-dynamodb.md) | Lock distribuido con escrituras condicionales de DynamoDB. |
| [`axiz-adapter-lock-redis`](adapters/axiz-adapter-lock-redis.md) | Lock distribuido en Redis. |
| [`axiz-adapter-messaging-kafka`](adapters/axiz-adapter-messaging-kafka.md) | Adaptador publicador y consumidor de Amazon MSK (Kafka) con autenticación IAM. |
| [`axiz-adapter-messaging-sns`](adapters/axiz-adapter-messaging-sns.md) | Adaptador publicador de Amazon SNS (estándar y FIFO). |
| [`axiz-adapter-messaging-sqs`](adapters/axiz-adapter-messaging-sqs.md) | Adaptador publicador y consumidor de Amazon SQS (estándar y FIFO). |
| [`axiz-adapter-observability-aws`](adapters/axiz-adapter-observability-aws.md) | Instrumentación con Micrometer sobre `axiz-adapter-aws`; aquí no se duplica ninguna implementación de credenciales ni de transporte. |
| [`axiz-adapter-outbox-jdbc`](adapters/axiz-adapter-outbox-jdbc.md) | Persistencia del outbox y recursos de esquema sobre JDBC. |
| [`axiz-adapter-outbox-r2dbc`](adapters/axiz-adapter-outbox-r2dbc.md) | Outbox transaccional en Aurora PostgreSQL sobre R2DBC: escritor no bloqueante y almacén del relay. |
| [`axiz-adapter-saga-jdbc`](adapters/axiz-adapter-saga-jdbc.md) | Persistencia de sagas y recursos de esquema sobre JDBC. |
| [`axiz-adapter-secrets-aws`](adapters/axiz-adapter-secrets-aws.md) | Resolvedores de secretos para AWS Secrets Manager y Parameter Store. |
| [`axiz-adapter-secrets-file`](adapters/axiz-adapter-secrets-file.md) | Adaptadores de secretos desde archivos y variables de entorno, con refresco acotado y ciclo de vida propio. |
| [`axiz-adapter-security-redis`](adapters/axiz-adapter-security-redis.md) | Limitador de tasa distribuido y almacén de nonces contra repetición sobre Redis (ElastiCache). |
| [`axiz-adapter-sequence-dynamodb`](adapters/axiz-adapter-sequence-dynamodb.md) | Almacén de secuencias sobre un contador atómico de DynamoDB. |
| [`axiz-adapter-sequence-jdbc`](adapters/axiz-adapter-sequence-jdbc.md) | Persistencia de secuencias y recursos de esquema sobre JDBC. |
| [`axiz-adapter-sequence-r2dbc`](adapters/axiz-adapter-sequence-r2dbc.md) | Reservas nativas de contadores en PostgreSQL para servicios reactivos. Implementa `ReactiveSequenceStore` |
| [`axiz-adapter-storage-s3`](adapters/axiz-adapter-storage-s3.md) | Almacenamiento de objetos en Amazon S3 con SSE-KMS y URL prefirmadas. |
| [`axiz-adapter-tokenization-dynamodb`](adapters/axiz-adapter-tokenization-dynamodb.md) | Bóveda de tokens atómica sobre DynamoDB, con construcción compartida de peticiones e I/O asíncrona nativa. |
| [`axiz-adapter-tokenization-redis`](adapters/axiz-adapter-tokenization-redis.md) | Presupuestos distribuidos para la bóveda. Implementa `TokenBudget` y `AsyncTokenBudget` usando |

## Capa 2: kits de persistencia

| Módulo | Descripción |
|---|---|
| [`axiz-kit-audit-reactor`](kits/axiz-kit-audit-reactor.md) | Contrato Reactor, independiente del proveedor, para el estado transaccional de la cadena de auditoría. |
| [`axiz-kit-dynamodb`](kits/axiz-kit-dynamodb.md) | Repositorio genérico de DynamoDB sobre el Enhanced Client, con paginación por cursor y convertidores. |
| [`axiz-kit-jdbc`](kits/axiz-kit-jdbc.md) | Conexiones JDBC conscientes de transacciones, utilidades de SQLSTATE y transacciones locales. |
| [`axiz-kit-jpa`](kits/axiz-kit-jpa.md) | Entidad base JPA con UUIDv7, auditoría, versionado y convertidores de atributos cifrados. |
| [`axiz-kit-outbox-reactor`](kits/axiz-kit-outbox-reactor.md) | Contrato Reactor, independiente del proveedor, para escrituras transaccionales en el outbox. |
| [`axiz-kit-r2dbc`](kits/axiz-kit-r2dbc.md) | Acceso no bloqueante a Aurora PostgreSQL sobre R2DBC: SQL con parámetros nombrados, transacciones y un contrato neutral de proveedor de contraseñas. |
| [`axiz-kit-sql`](kits/axiz-kit-sql.md) | Validación de identificadores SQL independiente de los drivers de base de datos. |
| [`kits`](kits/kits.md) | APIs públicas de tecnología que usan los adaptadores de persistencia de los servicios. Los kits tienen scope compile y se limitan a los paquetes de persistencia. No dependen de Spring ni de capas superiores. |

## Capa 3: Spring Boot

| Módulo | Descripción |
|---|---|
| [`axiz-audit-kafka-spring-boot-autoconfigure`](spring/axiz-audit-kafka-spring-boot-autoconfigure.md) | Destino de auditoría Kafka con publicación nativa síncrona y reactiva. |
| [`axiz-audit-kafka-spring-boot-starter`](spring/axiz-audit-kafka-spring-boot-starter.md) | Publicación de auditoría en Kafka. |
| [`axiz-audit-spring-boot-autoconfigure`](spring/axiz-audit-spring-boot-autoconfigure.md) | `axiz.audit.mode=ASYNC\|DURABLE\|SYNC` es el único selector de entrega; los selectores booleanos son |
| [`axiz-audit-spring-boot-starter`](spring/axiz-audit-spring-boot-starter.md) | @Auditable, eventos de auditoría saneados y publicadores asíncronos (log, MSK, SQS). |
| [`axiz-audit-sqs-spring-boot-autoconfigure`](spring/axiz-audit-sqs-spring-boot-autoconfigure.md) | Destino de auditoría SQS con publicación nativa síncrona y reactiva. |
| [`axiz-audit-sqs-spring-boot-starter`](spring/axiz-audit-sqs-spring-boot-starter.md) | Publicación de auditoría en SQS. |
| [`axiz-aws-spring-boot-autoconfigure`](spring/axiz-aws-spring-boot-autoconfigure.md) | Identidad de workload AWS, ciclo de vida y cableado de transporte compartidos. |
| [`axiz-cache-redis-spring-boot-autoconfigure`](spring/axiz-cache-redis-spring-boot-autoconfigure.md) | Integración de caché con Redis. |
| [`axiz-cache-redis-spring-boot-starter`](spring/axiz-cache-redis-spring-boot-starter.md) | Caché con Redis. |
| [`axiz-cache-spring-boot-autoconfigure`](spring/axiz-cache-spring-boot-autoconfigure.md) | @AxizCacheable con Caffeine como L1 y Redis (ElastiCache) como L2, TTL por caché y tolerancia a fallos (autoconfiguración). |
| [`axiz-cache-spring-boot-starter`](spring/axiz-cache-spring-boot-starter.md) | @AxizCacheable con Caffeine como L1 y Redis (ElastiCache) como L2, TTL por caché y tolerancia a fallos. |
| [`axiz-config-aws-spring-boot-autoconfigure`](spring/axiz-config-aws-spring-boot-autoconfigure.md) | Bootstrap de AWS Parameter Store con credenciales propias y selectores inmutables. |
| [`axiz-config-aws-spring-boot-starter`](spring/axiz-config-aws-spring-boot-starter.md) | Configuración opcional desde AWS Parameter Store. |
| [`axiz-config-spring-boot-autoconfigure`](spring/axiz-config-spring-boot-autoconfigure.md) | ConfigMaps montados como archivos y convenciones de Parameter Store con validación temprana (autoconfiguración). |
| [`axiz-config-spring-boot-starter`](spring/axiz-config-spring-boot-starter.md) | ConfigMaps montados como archivos y convenciones de Parameter Store con validación temprana. |
| [`axiz-crypto-kms-spring-boot-autoconfigure`](spring/axiz-crypto-kms-spring-boot-autoconfigure.md) | Integración con AWS KMS para cifrado de sobre. |
| [`axiz-crypto-kms-spring-boot-starter`](spring/axiz-crypto-kms-spring-boot-starter.md) | Cifrado de sobre con AWS KMS. |
| [`axiz-crypto-spring-boot-autoconfigure`](spring/axiz-crypto-spring-boot-autoconfigure.md) | Cifrado de campos con rotación de claves, cifrado de sobre con KMS, índice ciego, hash de contraseñas y JOSE (autoconfiguración). |
| [`axiz-crypto-spring-boot-starter`](spring/axiz-crypto-spring-boot-starter.md) | Cifrado de campos con rotación de claves, cifrado de sobre con KMS, índice ciego, hash de contraseñas y JOSE. |
| [`axiz-dynamodb-spring-boot-autoconfigure`](spring/axiz-dynamodb-spring-boot-autoconfigure.md) | DynamoDB Enhanced Client, nombres de tabla por ambiente y repositorios genéricos (autoconfiguración). |
| [`axiz-dynamodb-spring-boot-starter`](spring/axiz-dynamodb-spring-boot-starter.md) | DynamoDB Enhanced Client, nombres de tabla por ambiente y repositorios genéricos. |
| [`axiz-errors-spring-boot-autoconfigure`](spring/axiz-errors-spring-boot-autoconfigure.md) | Respuestas de error RFC 9457 con el catálogo de errores externalizado para las pilas servlet y reactiva (autoconfiguración). |
| [`axiz-errors-spring-boot-starter`](spring/axiz-errors-spring-boot-starter.md) | Respuestas de error RFC 9457 con el catálogo de errores externalizado para las pilas servlet y reactiva. |
| [`axiz-featureflags-spring-boot-autoconfigure`](spring/axiz-featureflags-spring-boot-autoconfigure.md) | Feature flags desde ConfigMap o Parameter Store expuestos mediante OpenFeature (autoconfiguración). |
| [`axiz-featureflags-spring-boot-starter`](spring/axiz-featureflags-spring-boot-starter.md) | Feature flags desde ConfigMap o Parameter Store expuestos mediante OpenFeature. |
| [`axiz-foundation-spring-boot-autoconfigure`](spring/axiz-foundation-spring-boot-autoconfigure.md) | Dinero, tiempo y calendario de negocio, identificadores y validación de documentos para Spring Boot (autoconfiguración). |
| [`axiz-foundation-spring-boot-starter`](spring/axiz-foundation-spring-boot-starter.md) | Dinero, tiempo y calendario de negocio, identificadores y validación de documentos para Spring Boot. |
| [`axiz-http-client-spring-boot-autoconfigure`](spring/axiz-http-client-spring-boot-autoconfigure.md) | Clientes declarativos @HttpExchange con configuración por cliente, autenticación saliente, resiliencia, traducción de errores y logs (autoconfiguración). |
| [`axiz-http-client-spring-boot-starter`](spring/axiz-http-client-spring-boot-starter.md) | Clientes declarativos @HttpExchange con configuración por cliente, autenticación saliente, resiliencia, traducción de errores y logs. |
| [`axiz-i18n-spring-boot-autoconfigure`](spring/axiz-i18n-spring-boot-autoconfigure.md) | Paquetes de mensajes, resolución de Accept-Language y localización de mensajes de error (autoconfiguración). |
| [`axiz-i18n-spring-boot-starter`](spring/axiz-i18n-spring-boot-starter.md) | Paquetes de mensajes, resolución de Accept-Language y localización de mensajes de error. |
| [`axiz-idempotency-dynamodb-spring-boot-autoconfigure`](spring/axiz-idempotency-dynamodb-spring-boot-autoconfigure.md) | Integración de idempotencia con DynamoDB. |
| [`axiz-idempotency-dynamodb-spring-boot-starter`](spring/axiz-idempotency-dynamodb-spring-boot-starter.md) | Idempotencia con DynamoDB. |
| [`axiz-idempotency-redis-spring-boot-autoconfigure`](spring/axiz-idempotency-redis-spring-boot-autoconfigure.md) | Integración de idempotencia con Redis. |
| [`axiz-idempotency-redis-spring-boot-starter`](spring/axiz-idempotency-redis-spring-boot-starter.md) | Idempotencia con Redis. |
| [`axiz-idempotency-spring-boot-autoconfigure`](spring/axiz-idempotency-spring-boot-autoconfigure.md) | Endpoints @Idempotent con Idempotency-Key y respuestas guardadas en Redis o DynamoDB (autoconfiguración). |
| [`axiz-idempotency-spring-boot-starter`](spring/axiz-idempotency-spring-boot-starter.md) | Endpoints @Idempotent con Idempotency-Key y respuestas guardadas en Redis o DynamoDB. |
| [`axiz-inbox-dynamodb-spring-boot-autoconfigure`](spring/axiz-inbox-dynamodb-spring-boot-autoconfigure.md) | Integración de inbox con DynamoDB. |
| [`axiz-inbox-dynamodb-spring-boot-starter`](spring/axiz-inbox-dynamodb-spring-boot-starter.md) | Inbox opcional con DynamoDB. |
| [`axiz-inbox-jdbc-spring-boot-autoconfigure`](spring/axiz-inbox-jdbc-spring-boot-autoconfigure.md) | Integración de inbox con JDBC. |
| [`axiz-inbox-jdbc-spring-boot-starter`](spring/axiz-inbox-jdbc-spring-boot-starter.md) | Inbox opcional con JDBC. |
| [`axiz-inbox-r2dbc-spring-boot-autoconfigure`](spring/axiz-inbox-r2dbc-spring-boot-autoconfigure.md) | Integración de inbox con R2DBC. |
| [`axiz-inbox-r2dbc-spring-boot-starter`](spring/axiz-inbox-r2dbc-spring-boot-starter.md) | Inbox opcional con R2DBC. |
| [`axiz-inbox-spring-boot-autoconfigure`](spring/axiz-inbox-spring-boot-autoconfigure.md) | Consumidores idempotentes: inbox sobre Aurora PostgreSQL o DynamoDB (autoconfiguración). |
| [`axiz-inbox-spring-boot-starter`](spring/axiz-inbox-spring-boot-starter.md) | Consumidores idempotentes: inbox sobre Aurora PostgreSQL o DynamoDB. |
| [`axiz-jdbc-spring-boot-autoconfigure`](spring/axiz-jdbc-spring-boot-autoconfigure.md) | Lado Spring de JDBC para las capacidades que lo usan (outbox, inbox, saga, sequence, scheduling): |
| [`axiz-jpa-aws-spring-boot-autoconfigure`](spring/axiz-jpa-aws-spring-boot-autoconfigure.md) | Failover del wrapper JDBC de AWS y autenticación IAM para JPA. |
| [`axiz-jpa-aws-spring-boot-starter`](spring/axiz-jpa-aws-spring-boot-starter.md) | Wrapper JDBC de AWS y credenciales IAM opcionales. |
| [`axiz-jpa-spring-boot-autoconfigure`](spring/axiz-jpa-spring-boot-autoconfigure.md) | Convenciones JPA neutrales respecto del proveedor. Los consumidores seleccionan `axiz-jpa-spring-boot-starter`; |
| [`axiz-jpa-spring-boot-starter`](spring/axiz-jpa-spring-boot-starter.md) | Aurora PostgreSQL con Hibernate 7: entidades base, auditoría, cifrado, réplicas de lectura y valores por defecto seguros. |
| [`axiz-kafka-spring-boot-autoconfigure`](spring/axiz-kafka-spring-boot-autoconfigure.md) | Validación compartida del transporte Kafka efectivo para mensajería y auditoría. Se exige SSL o SASL_SSL y verificación del par y del hostname, salvo una excepción explícita local/test/ci (`axiz.transport.allow-insecure-data-stores=true`). |
| [`axiz-lock-dynamodb-spring-boot-autoconfigure`](spring/axiz-lock-dynamodb-spring-boot-autoconfigure.md) | Integración de locks con DynamoDB. |
| [`axiz-lock-dynamodb-spring-boot-starter`](spring/axiz-lock-dynamodb-spring-boot-starter.md) | Locks con DynamoDB. |
| [`axiz-lock-redis-spring-boot-autoconfigure`](spring/axiz-lock-redis-spring-boot-autoconfigure.md) | Integración de locks con Redis. |
| [`axiz-lock-redis-spring-boot-starter`](spring/axiz-lock-redis-spring-boot-starter.md) | Locks con Redis. |
| [`axiz-lock-spring-boot-autoconfigure`](spring/axiz-lock-spring-boot-autoconfigure.md) | Locks distribuidos sobre Redis o DynamoDB con la plantilla de lock (autoconfiguración). |
| [`axiz-lock-spring-boot-starter`](spring/axiz-lock-spring-boot-starter.md) | Locks distribuidos sobre Redis o DynamoDB con la plantilla de lock. |
| [`axiz-logging-spring-boot-autoconfigure`](spring/axiz-logging-spring-boot-autoconfigure.md) | Logs JSON estructurados, registro de operaciones con enmascaramiento y logs de peticiones HTTP (autoconfiguración). |
| [`axiz-logging-spring-boot-starter`](spring/axiz-logging-spring-boot-starter.md) | Logs JSON estructurados, registro de operaciones con enmascaramiento y logs de peticiones HTTP. |
| [`axiz-messaging-kafka-spring-boot-autoconfigure`](spring/axiz-messaging-kafka-spring-boot-autoconfigure.md) | Integración Kafka para mensajería CloudEvents. |
| [`axiz-messaging-kafka-spring-boot-starter`](spring/axiz-messaging-kafka-spring-boot-starter.md) | Mensajería CloudEvents sobre Kafka. |
| [`axiz-messaging-msk-spring-boot-autoconfigure`](spring/axiz-messaging-msk-spring-boot-autoconfigure.md) | Integración MSK para mensajería CloudEvents. |
| [`axiz-messaging-msk-spring-boot-starter`](spring/axiz-messaging-msk-spring-boot-starter.md) | Mensajería CloudEvents sobre MSK. |
| [`axiz-messaging-sns-spring-boot-autoconfigure`](spring/axiz-messaging-sns-spring-boot-autoconfigure.md) | Integración SNS para mensajería CloudEvents. |
| [`axiz-messaging-sns-spring-boot-starter`](spring/axiz-messaging-sns-spring-boot-starter.md) | Mensajería CloudEvents sobre SNS. |
| [`axiz-messaging-spring-boot-autoconfigure`](spring/axiz-messaging-spring-boot-autoconfigure.md) | Los publicadores exponen el presupuesto visible para el llamador mediante `BoundedPublisher`. Kafka usa la |
| [`axiz-messaging-spring-boot-starter`](spring/axiz-messaging-spring-boot-starter.md) | Publicadores y consumidores CloudEvents para MSK, SQS y SNS con reintentos, DLQ e integración con inbox. |
| [`axiz-messaging-sqs-spring-boot-autoconfigure`](spring/axiz-messaging-sqs-spring-boot-autoconfigure.md) | Integración SQS para mensajería CloudEvents. |
| [`axiz-messaging-sqs-spring-boot-starter`](spring/axiz-messaging-sqs-spring-boot-starter.md) | Mensajería CloudEvents sobre SQS. |
| [`axiz-metrics-spring-boot-autoconfigure`](spring/axiz-metrics-spring-boot-autoconfigure.md) | Métricas genéricas de eventos con @Metered, catálogo, tags comunes y protección de cardinalidad (autoconfiguración). |
| [`axiz-metrics-spring-boot-starter`](spring/axiz-metrics-spring-boot-starter.md) | Métricas genéricas de eventos con @Metered, catálogo, tags comunes y protección de cardinalidad. |
| [`axiz-notification-spring-boot-autoconfigure`](spring/axiz-notification-spring-boot-autoconfigure.md) | Solicitudes de notificación publicadas como eventos hacia el servicio que las entrega (autoconfiguración). |
| [`axiz-notification-spring-boot-starter`](spring/axiz-notification-spring-boot-starter.md) | Solicitudes de notificación publicadas como eventos hacia el servicio que las entrega. |
| [`axiz-observability-spring-boot-autoconfigure`](spring/axiz-observability-spring-boot-autoconfigure.md) | OpenTelemetry mediante Micrometer: spans del framework, eventos estructurados, protección de datos personales, grupos de salud y trazas del AWS SDK (autoconfiguración). |
| [`axiz-observability-spring-boot-starter`](spring/axiz-observability-spring-boot-starter.md) | OpenTelemetry mediante Micrometer: spans del framework, eventos estructurados, protección de datos personales, grupos de salud y trazas del AWS SDK. |
| [`axiz-outbox-jdbc-spring-boot-autoconfigure`](spring/axiz-outbox-jdbc-spring-boot-autoconfigure.md) | Persistencia JDBC del outbox transaccional. |
| [`axiz-outbox-jdbc-spring-boot-starter`](spring/axiz-outbox-jdbc-spring-boot-starter.md) | Integración del outbox JDBC con el relay compartido. |
| [`axiz-outbox-r2dbc-spring-boot-autoconfigure`](spring/axiz-outbox-r2dbc-spring-boot-autoconfigure.md) | Persistencia R2DBC del outbox transaccional. |
| [`axiz-outbox-r2dbc-spring-boot-starter`](spring/axiz-outbox-r2dbc-spring-boot-starter.md) | Integración del outbox R2DBC con el relay compartido. |
| [`axiz-outbox-spring-boot-autoconfigure`](spring/axiz-outbox-spring-boot-autoconfigure.md) | El arranque del relay valida el presupuesto real del `EventPublisher`, incluidos los wrappers de |
| [`axiz-outbox-spring-boot-starter`](spring/axiz-outbox-spring-boot-starter.md) | Outbox transaccional sobre Aurora PostgreSQL con relay en segundo plano. |
| [`axiz-r2dbc-aws-spring-boot-autoconfigure`](spring/axiz-r2dbc-aws-spring-boot-autoconfigure.md) | Credenciales IAM de AWS para los pools R2DBC de escritura y lectura. |
| [`axiz-r2dbc-aws-spring-boot-starter`](spring/axiz-r2dbc-aws-spring-boot-starter.md) | Autenticación IAM de AWS opcional para R2DBC. |
| [`axiz-r2dbc-spring-boot-autoconfigure`](spring/axiz-r2dbc-spring-boot-autoconfigure.md) | Aurora PostgreSQL para servicios reactivos sobre R2DBC: tokens IAM, valores por defecto del pool, Flyway sobre JDBC, ReactiveSql transaccional, auditoría, columnas cifradas y de dinero, endpoint de lectura (autoconfiguración). |
| [`axiz-r2dbc-spring-boot-starter`](spring/axiz-r2dbc-spring-boot-starter.md) | Aurora PostgreSQL para servicios reactivos sobre R2DBC. |
| [`axiz-redis-spring-boot-autoconfigure`](spring/axiz-redis-spring-boot-autoconfigure.md) | Protección de transporte Redis compartida, instalada por las capacidades que usan esta tecnología. No crea clientes de negocio ni agrega un nuevo selector de almacén. |
| [`axiz-resilience-spring-boot-autoconfigure`](spring/axiz-resilience-spring-boot-autoconfigure.md) | Valores por defecto de Resilience4j (circuit breaker, bulkhead) y @Retryable de Spring configurados por propiedades (autoconfiguración). |
| [`axiz-resilience-spring-boot-starter`](spring/axiz-resilience-spring-boot-starter.md) | Valores por defecto de Resilience4j (circuit breaker, bulkhead) y @Retryable de Spring configurados por propiedades. |
| [`axiz-saga-jdbc-spring-boot-autoconfigure`](spring/axiz-saga-jdbc-spring-boot-autoconfigure.md) | Persistencia JDBC opcional de sagas. Se instala `axiz-saga-jdbc-spring-boot-starter` para obtener las |
| [`axiz-saga-jdbc-spring-boot-starter`](spring/axiz-saga-jdbc-spring-boot-starter.md) | Persistencia JDBC opcional de sagas. Se instala `axiz-saga-jdbc-spring-boot-starter` para obtener las |
| [`axiz-saga-spring-boot-autoconfigure`](spring/axiz-saga-spring-boot-autoconfigure.md) | Orquestador de sagas con estado persistido en PostgreSQL y recuperación de sagas detenidas (autoconfiguración). |
| [`axiz-saga-spring-boot-starter`](spring/axiz-saga-spring-boot-starter.md) | Orquestador de sagas con estado persistido en PostgreSQL y recuperación de sagas detenidas. |
| [`axiz-scheduling-dynamodb-spring-boot-autoconfigure`](spring/axiz-scheduling-dynamodb-spring-boot-autoconfigure.md) | Integración de scheduling con DynamoDB. |
| [`axiz-scheduling-dynamodb-spring-boot-starter`](spring/axiz-scheduling-dynamodb-spring-boot-starter.md) | Scheduling opcional con DynamoDB. |
| [`axiz-scheduling-jdbc-spring-boot-autoconfigure`](spring/axiz-scheduling-jdbc-spring-boot-autoconfigure.md) | Integración de scheduling con JDBC. |
| [`axiz-scheduling-jdbc-spring-boot-starter`](spring/axiz-scheduling-jdbc-spring-boot-starter.md) | Scheduling opcional con JDBC. |
| [`axiz-scheduling-r2dbc-spring-boot-autoconfigure`](spring/axiz-scheduling-r2dbc-spring-boot-autoconfigure.md) | Integración de scheduling con R2DBC. |
| [`axiz-scheduling-r2dbc-spring-boot-starter`](spring/axiz-scheduling-r2dbc-spring-boot-starter.md) | Scheduling opcional con R2DBC. |
| [`axiz-scheduling-spring-boot-autoconfigure`](spring/axiz-scheduling-spring-boot-autoconfigure.md) | Ejecución única por clúster de tareas programadas con ShedLock (JDBC o DynamoDB) (autoconfiguración). |
| [`axiz-scheduling-spring-boot-starter`](spring/axiz-scheduling-spring-boot-starter.md) | Ejecución única por clúster de tareas programadas con ShedLock (JDBC o DynamoDB). |
| [`axiz-secrets-aws-spring-boot-autoconfigure`](spring/axiz-secrets-aws-spring-boot-autoconfigure.md) | Integración AWS para la resolución de secretos. |
| [`axiz-secrets-aws-spring-boot-starter`](spring/axiz-secrets-aws-spring-boot-starter.md) | Fuentes de secretos en AWS. |
| [`axiz-secrets-file-spring-boot-autoconfigure`](spring/axiz-secrets-file-spring-boot-autoconfigure.md) | Integración de archivos para la resolución de secretos. |
| [`axiz-secrets-file-spring-boot-starter`](spring/axiz-secrets-file-spring-boot-starter.md) | Fuentes de secretos en archivos. |
| [`axiz-secrets-spring-boot-autoconfigure`](spring/axiz-secrets-spring-boot-autoconfigure.md) | SecretResolver sobre archivos montados, variables de entorno y AWS Secrets Manager o Parameter Store (autoconfiguración). |
| [`axiz-secrets-spring-boot-starter`](spring/axiz-secrets-spring-boot-starter.md) | SecretResolver sobre archivos montados, variables de entorno y AWS Secrets Manager o Parameter Store. |
| [`axiz-security-redis-spring-boot-autoconfigure`](spring/axiz-security-redis-spring-boot-autoconfigure.md) | Integración con Redis para el límite de tasa a nivel de clúster. |
| [`axiz-security-redis-spring-boot-starter`](spring/axiz-security-redis-spring-boot-starter.md) | Límite de tasa a nivel de clúster sobre Redis. |
| [`axiz-security-spring-boot-autoconfigure`](spring/axiz-security-spring-boot-autoconfigure.md) | Resource server OAuth2 con lista de emisores permitidos, mapeo de claims, seguridad de métodos, cabeceras y límite de tasa (autoconfiguración). |
| [`axiz-security-spring-boot-starter`](spring/axiz-security-spring-boot-starter.md) | Resource server OAuth2 con lista de emisores permitidos, mapeo de claims, seguridad de métodos, cabeceras y límite de tasa. |
| [`axiz-sensitive-spring-boot-autoconfigure`](spring/axiz-sensitive-spring-boot-autoconfigure.md) | Enmascaramiento de campos @Sensitive y @Mask en respuestas JSON, logs y auditoría (autoconfiguración). |
| [`axiz-sensitive-spring-boot-starter`](spring/axiz-sensitive-spring-boot-starter.md) | Enmascaramiento de campos @Sensitive y @Mask en respuestas JSON, logs y auditoría. |
| [`axiz-sequence-dynamodb-spring-boot-autoconfigure`](spring/axiz-sequence-dynamodb-spring-boot-autoconfigure.md) | Integración de secuencias con DynamoDB. |
| [`axiz-sequence-dynamodb-spring-boot-starter`](spring/axiz-sequence-dynamodb-spring-boot-starter.md) | Secuencias opcionales con DynamoDB. |
| [`axiz-sequence-jdbc-spring-boot-autoconfigure`](spring/axiz-sequence-jdbc-spring-boot-autoconfigure.md) | Integración de secuencias con JDBC. |
| [`axiz-sequence-jdbc-spring-boot-starter`](spring/axiz-sequence-jdbc-spring-boot-starter.md) | Secuencias opcionales con JDBC. |
| [`axiz-sequence-r2dbc-spring-boot-autoconfigure`](spring/axiz-sequence-r2dbc-spring-boot-autoconfigure.md) | Integración de secuencias con R2DBC. |
| [`axiz-sequence-r2dbc-spring-boot-starter`](spring/axiz-sequence-r2dbc-spring-boot-starter.md) | Secuencias opcionales con R2DBC. |
| [`axiz-sequence-spring-boot-autoconfigure`](spring/axiz-sequence-spring-boot-autoconfigure.md) | Secuencias atómicas con formato sobre JDBC, R2DBC nativo o DynamoDB, seleccionadas según la pila de la aplicación. |
| [`axiz-sequence-spring-boot-starter`](spring/axiz-sequence-spring-boot-starter.md) | Secuencias atómicas con formato sobre PostgreSQL o DynamoDB. |
| [`axiz-soap-spring-boot-autoconfigure`](spring/axiz-soap-spring-boot-autoconfigure.md) | Clientes SOAP con parseo XML seguro: timeouts, WS-Security, mapeo de faults, logs enmascarados y observaciones (autoconfiguración). |
| [`axiz-soap-spring-boot-starter`](spring/axiz-soap-spring-boot-starter.md) | Clientes SOAP con parseo XML seguro: timeouts, WS-Security, mapeo de faults, logs enmascarados y observaciones. |
| [`axiz-storage-s3-spring-boot-autoconfigure`](spring/axiz-storage-s3-spring-boot-autoconfigure.md) | Integración con Amazon S3 para almacenamiento de objetos. |
| [`axiz-storage-s3-spring-boot-starter`](spring/axiz-storage-s3-spring-boot-starter.md) | Almacenamiento de objetos en S3 con URLs firmadas acotadas. |
| [`axiz-storage-spring-boot-autoconfigure`](spring/axiz-storage-spring-boot-autoconfigure.md) | Almacenamiento de objetos en Amazon S3 con SSE-KMS y URLs prefirmadas (autoconfiguración). |
| [`axiz-storage-spring-boot-starter`](spring/axiz-storage-spring-boot-starter.md) | Almacenamiento de objetos en Amazon S3 con SSE-KMS y URLs prefirmadas. |
| [`axiz-tenancy-spring-boot-autoconfigure`](spring/axiz-tenancy-spring-boot-autoconfigure.md) | Resolución del tenant desde el token o una cabecera confiable y propagación a HTTP, mensajería e Hibernate (autoconfiguración). |
| [`axiz-tenancy-spring-boot-starter`](spring/axiz-tenancy-spring-boot-starter.md) | Resolución del tenant desde el token o una cabecera confiable y propagación a HTTP, mensajería e Hibernate. |
| [`axiz-test-spring-boot-starter`](spring/axiz-test-spring-boot-starter.md) | Soporte de pruebas: Testcontainers (PostgreSQL, LocalStack, Kafka, Redis), WireMock, reglas ArchUnit, BlockHound y fixtures. |
| [`axiz-tokenization-pan-spring-boot-autoconfigure`](spring/axiz-tokenization-pan-spring-boot-autoconfigure.md) | Registra `PanTokenProfile`, perfil `pan.luhn` versión 1, en el motor genérico de la bóveda. |
| [`axiz-tokenization-pan-spring-boot-starter`](spring/axiz-tokenization-pan-spring-boot-starter.md) | Starter sin código para habilitar el perfil PAN en una bóveda genérica. |
| [`axiz-tokenization-spring-boot-autoconfigure`](spring/axiz-tokenization-spring-boot-autoconfigure.md) | Aporta `Tokenizer` en la pila imperativa y `ReactiveTokenizer` en la pila reactiva. |
| [`axiz-tokenization-spring-boot-starter`](spring/axiz-tokenization-spring-boot-starter.md) | Punto de entrada solo de dependencias para el consumidor remoto. `axiz.tokenization.mode` se configura |
| [`axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure`](spring/axiz-tokenization-vault-dynamodb-spring-boot-autoconfigure.md) | Integra la bóveda genérica con DynamoDB. Usa el adaptador transaccional; no contiene otro motor, |
| [`axiz-tokenization-vault-dynamodb-spring-boot-starter`](spring/axiz-tokenization-vault-dynamodb-spring-boot-starter.md) | Starter sin código que incorpora la integración DynamoDB de la bóveda central. |
| [`axiz-tokenization-vault-redis-spring-boot-autoconfigure`](spring/axiz-tokenization-vault-redis-spring-boot-autoconfigure.md) | Compone los presupuestos distribuidos de la bóveda con una conexión Redis/Valkey exclusiva. |
| [`axiz-tokenization-vault-redis-spring-boot-starter`](spring/axiz-tokenization-vault-redis-spring-boot-starter.md) | Starter sin código para seleccionar Redis/Valkey como presupuesto compartido de la bóveda. |
| [`axiz-tokenization-vault-spring-boot-autoconfigure`](spring/axiz-tokenization-vault-spring-boot-autoconfigure.md) | Composición del servidor genérico de tokenización. Requiere configuración explícita y los |
| [`axiz-tokenization-vault-spring-boot-starter`](spring/axiz-tokenization-vault-spring-boot-starter.md) | Starter de dependencias para el servidor genérico de bóveda. Incluye únicamente su |
| [`axiz-web-spring-boot-autoconfigure`](spring/axiz-web-spring-boot-autoconfigure.md) | Correlación, validación del catálogo de cabeceras, paginación, versionado de API, CORS y metadatos OpenAPI (autoconfiguración). |
| [`axiz-web-spring-boot-starter`](spring/axiz-web-spring-boot-starter.md) | Correlación, validación del catálogo de cabeceras, paginación, versionado de API, CORS y metadatos OpenAPI. |
| [`spring`](spring/spring.md) | Integración Spring Boot 4.1 para las capacidades del framework. |

## Capa 4: estructuras

| Módulo | Descripción |
|---|---|
| [`axiz-structure-batch-api`](structures/axiz-structure-batch-api.md) | Estilo batch sobre Spring Batch: jobs reiniciables, pasos por bloques, archivos en S3 y ejecución como CronJob o con ShedLock (contratos, Java simple). |
| [`axiz-structure-batch-spring-boot-starter`](structures/axiz-structure-batch-spring-boot-starter.md) | Estilo batch sobre Spring Batch: jobs reiniciables, pasos por bloques, archivos en S3 y ejecución como CronJob o con ShedLock. |
| [`axiz-structure-bff-api`](structures/axiz-structure-bff-api.md) | Backend for frontend: composición en paralelo, resultados parciales, adaptación por canal y caché de lectura (contratos, Java simple). |
| [`axiz-structure-bff-spring-boot-starter`](structures/axiz-structure-bff-spring-boot-starter.md) | Backend for frontend: composición en paralelo, resultados parciales, adaptación por canal y caché de lectura. |
| [`axiz-structure-eda-spring-boot-starter`](structures/axiz-structure-eda-spring-boot-starter.md) | Estilo orientado a eventos: handlers tipados, consumo idempotente, publicación por outbox, reintentos y DLQ. |
| [`axiz-structure-hexagonal-api`](structures/axiz-structure-hexagonal-api.md) | Estilo hexagonal: casos de uso, puertos y adaptadores, transacción por caso de uso y eventos de dominio al confirmar (contratos, Java simple). |
| [`axiz-structure-hexagonal-spring-boot-starter`](structures/axiz-structure-hexagonal-spring-boot-starter.md) | Estilo hexagonal: casos de uso, puertos y adaptadores, transacción por caso de uso y eventos de dominio al confirmar. |
| [`axiz-structure-layered-api`](structures/axiz-structure-layered-api.md) | Estilo en capas para servicios CRUD simples: controller, service y repository (contratos, Java simple). |
| [`axiz-structure-layered-spring-boot-starter`](structures/axiz-structure-layered-spring-boot-starter.md) | Estilo en capas para servicios CRUD simples: controller, service y repository. |
| [`structures`](structures/structures.md) | Estructuras de servicio: layered, hexagonal, BFF, EDA y batch. Cada estructura tiene contratos neutrales y su cableado Spring. CQRS es una extensión y está en extensions/. |

## Capa 4: extensiones

| Módulo | Descripción |
|---|---|
| [`axiz-extension-cqrs-api`](extensions/axiz-extension-cqrs-api.md) | CQRS sobre hexagonal: comandos, consultas, buses con un pipeline ordenado y proyecciones (contratos, Java simple). |
| [`axiz-extension-cqrs-spring-boot-starter`](extensions/axiz-extension-cqrs-spring-boot-starter.md) | CQRS sobre hexagonal: comandos, consultas, buses con un pipeline ordenado y proyecciones. |
| [`extensions`](extensions/extensions.md) | Patrones estructurales opcionales. CQRS extiende hexagonal con buses y comportamientos separados para comandos y consultas, y compone las capacidades del núcleo mediante cableado Spring. |

## Generación

| Módulo | Descripción |
|---|---|
| [`axiz-archetypes`](codegen/axiz-archetypes.md) | Definiciones de arquetipos: rol, framework por defecto, stack, starters, persistencia y despliegue de cada tipo de servicio. |
| [`axiz-codegen`](codegen/axiz-codegen.md) | Motor de generación compartido por axiz-initializr y axiz-cli: modelo y lint de OpenAPI, reglas de compatibilidad, plantillas y escritura segura. |
| [`axiz-codegen-templates`](codegen/axiz-codegen-templates.md) | Plantillas Mustache de los servicios generados, versionadas junto con el framework. |
| [`codegen`](codegen/codegen.md) | CLI e Initializr usan el mismo motor para producir proyectos coherentes con el framework. |

## Calidad

| Módulo | Descripción |
|---|---|
| [`axiz-architecture-tests`](quality/axiz-architecture-tests.md) | Pruebas ArchUnit del propio framework (`FrameworkArchitectureTest`): la capa 1 es Java simple (sin |
| [`axiz-archunit-rules`](quality/axiz-archunit-rules.md) | Reglas de arquitectura para los servicios de axiz: higiene general y un conjunto de reglas por estilo de arquitectura. |
| [`axiz-code-standards`](quality/axiz-code-standards.md) | Configuración de Checkstyle, SpotBugs, Semgrep, Spectral y gitleaks. |
| [`axiz-coverage-report`](quality/axiz-coverage-report.md) | Agrega los reportes de JaCoCo de todos los módulos con código de producción (`./mvnw -Pquality verify`, |
| [`axiz-store-contract-tests`](quality/axiz-store-contract-tests.md) | Comprobaciones de contrato que todo almacén de idempotencia y de inbox debe superar (propiedad, expiración, toma de control); se ejecutan contra fakes en las pruebas unitarias y contra backends reales en las de integración. |
| [`axiz-store-integration-tests`](quality/axiz-store-integration-tests.md) | Módulo interno, nunca publicado: no es una dependencia para los servicios. |
| [`axiz-test-containers`](quality/axiz-test-containers.md) | Herramientas para contenedores de pruebas, independientes de Spring y del core de producción. |
| [`axiz-test-support`](quality/axiz-test-support.md) | Fixtures de prueba para la capa 1: reloj mutable, publicador que registra eventos, aserciones de métricas y un fake de R2DBC en memoria. |
| [`quality`](quality/quality.md) | Los parents Maven aplican los estándares comunes; los servicios incorporan las reglas de |
