# Recetas de proveedores

Ejemplos ejecutables de consumidor para las opciones de proveedor que el flujo de pagos no ejercita.
Requieren JDK 25, Docker y los artefactos de Axiz instalados. Usan solo contenedores locales.

```bash
./mvnw -pl examples/provider-recipes verify
```

Este comando se ejecuta desde `axiz-demo`. Cada prueba arranca solo las configuraciones Spring
seleccionadas de los starters públicos declarados en este POM. Una aplicación debe depender del
subconjunto de starters que necesita, nunca de este módulo de ejemplo. Las pruebas son dueñas del
aprovisionamiento y de la limpieza de recursos.

Las recetas relacionales usan PostgreSQL, incluidos rollback, entrega duplicada, reserva de secuencias
y locks del planificador en competencia. Las migraciones SQL se aplican antes de que arranquen los
consumidores; los despliegues de producción las gestionan con su proceso normal de migraciones.

| Receta | Qué hace el consumidor | Límite ejercitado |
|---|---|---|
| `RelationalRecipesTest` | Reserva valores de secuencia JDBC/R2DBC, revierte trabajo de negocio, deduplica entregas del inbox, confirma o revierte eventos del outbox junto con las filas de negocio, excluye workers programados en competencia | PostgreSQL mediante configuraciones públicas de proveedores |
| `CloudRecipesTest` | Sube, descarga y borra contenido en S3, comparte contadores de secuencia en DynamoDB, coordina inbox, idempotencia y locks, resuelve valores de Secrets Manager y SSM, carga Parameter Store antes de que existan los beans | LocalStack; proveedores síncronos y nativos asíncronos |
| `RedisRecipesTest` | Guarda y desaloja datos en caché, protege locks y respuestas idempotentes, comparte límites de tasa | Valkey mediante proveedores Redis en ambos stacks; también verifica que los proveedores ausentes o desactivados no puedan declarar cobertura |
| `MessagingRecipesTest` | Publica mediante los proveedores de mensajería SQS y SNS y envía registros de auditoría saneados a destinos independientes de SQS y Kafka | LocalStack y Kafka; entrega real de productor y consumidor en ambos stacks |
| `IdentityRecipesTest` | Selecciona una identidad de credenciales, firma la autenticación expirable de RDS, configura un perfil IAM acotado al pool y el callback TLS/SASL de MSK, lee secretos montados | Contratos de identidad locales y I/O real de sistema de archivos; sin autenticación remota en RDS ni MSK |
| `CrossCuttingRecipesTest` | Registra operaciones con argumentos y resultados excluidos, observa resultados sin atributos secretos, resuelve mensajes localizados, propaga y limpia el contexto de tenant confiable | Contratos de consumidor Spring en proceso; la validación JWT y la recolección OTLP son límites separados |
| `OutboundRecipesTest` | Reintenta una lectura HTTP, se niega a repetir un pago sin clave, llama a SOAP después de rotar credenciales | Transportes HTTP/XML reales en loopback; producción requiere TLS |
| `RemoteTokenClientRecipeTest` | Tokeniza, busca, describe, revela y borra mediante `Tokenizer`; usa la identidad del suscriptor con `ReactiveTokenizer` | Contrato del protocolo del cliente remoto contra un fixture en loopback, no una bóveda de tokens desplegable |

Para ejecutar una receta se usa `./mvnw -pl examples/provider-recipes -Dtest=RedisRecipesTest test`.
La prueba nombrada muestra los imports, la configuración de starters seleccionada, las propiedades, el
aprovisionamiento y la propiedad de los recursos. Se usan solo los starters que necesita el servicio.
Los ejemplos mantienen a propósito explícita la selección de proveedores para que un servicio nunca
pase en silencio a un almacén en memoria.

El cliente de tokens devuelve un token opaco y los metadatos permitidos. La búsqueda usa la operación
de consulta de la bóveda; el texto cifrado y el índice de igualdad permanecen en la bóveda. El relay de
tokens usa la identidad validada de la petición o la identidad del suscriptor de Reactor. Un proceso
sin identidad entrante usa client credentials. Los reintentos automáticos de tokens están desactivados;
cualquier reintento de negocio debe usar su identificador de operación estable y el contrato de
idempotencia documentado de la bóveda.

Las pruebas de identidad locales no pueden demostrar la aplicación de IAM en AWS. Comprueban la
configuración y la firma sin una cuenta remota de AWS. LocalStack ejercita los contratos de la API de
AWS que usa el framework; producción necesita IAM, TLS e infraestructura propios del servicio. Ninguna
prueba imprime material secreto.

El módulo `coverage` de la demo lee los resultados reales de Surefire y Failsafe después de que se
ejecutan todos los módulos consumidores. Rechaza resultados ausentes, fallidos, omitidos o
desactualizados y exige cada opción de persistencia del catálogo del framework. `coverage.yaml`
registra la prueba y el nivel de comprobación de cada capacidad; `variants.yaml` añade obligaciones
explícitas de stack y de proveedor. Son comprobaciones de aceptación de la demo, separadas de las
pruebas unitarias, de integración y de arquitectura propias del framework.

La receta de notificaciones invoca `NotificationSender` y `ReactiveNotifications`, y luego verifica los
sobres reales `notification.requested.v1` recibidos mediante SNS -> SQS. La aplicación de entrega es
dueña de los proveedores de email, SMS y push; Axiz publica la solicitud, la correlación y los
parámetros de la plantilla.

Las recetas comunes se ejecutan en contextos servlet y reactivos. `PolicyRecipesTest` muestra
enmascaramiento, feature flags por canal y métricas; `CloudRecipesTest` realiza CRUD y paginación
DynamoDB con cursores ligados a la consulta, incluyendo rechazo de otra partición.
`MessagingRecipesTest` verifica también el publicador Kafka servlet con confirmación y lectura
real del broker. El consumidor SQS, sus DLQ y FIFO están en el ejemplo mínimo
[`sqs-consumer`](../sqs-consumer/README.md), que usa descubrimiento automático de starters.
