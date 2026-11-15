# Servlet y reactive

El stack decide el modelo de ejecución del servicio completo. Se combina con el arquetipo,
la estructura y las extensiones, y no constituye otra capa del framework.

| Stack | Entrada | I/O | Transacción |
|---|---|---|---|
| `servlet` | Spring MVC y virtual threads | Puertos y clientes síncronos | JPA/JDBC y `PlatformTransactionManager` |
| `reactive` | WebFlux y Reactor | Puertos asíncronos nativos y WebClient | R2DBC y `TransactionalOperator` |

Un servicio reactive no espera I/O en el event loop ni en los hilos que completan clientes
asíncronos. `boundedElastic` no convierte un adaptador bloqueante en soporte reactive.
Hexagonal, layered, bff y eda admiten este stack; batch, saga y SOAP son servlet.

## Límites del contexto

Para Redis y AWS, los puertos core usan `CompletionStage` o `Flow.Publisher`; Spring los adapta
mediante operadores diferidos que conservan errores y cancelación. R2DBC conserva Reactor de
forma nativa para que negocio y outbox participen en la misma transacción.
El dominio continúa independiente de frameworks terceros.

Las secuencias reservan bloques en una transacción independiente: un rollback del negocio no
debe devolver números que sigan en la caché de otra petición. `ReactiveSequences` usa DynamoDB
asíncrono o el puerto `ReactiveSequenceStore` del kit R2DBC. Sus pools de reserva son independientes
del pool de negocio y están acotados; esta capacidad admite huecos tras fallos o reinicios.

`ReactiveStorage` utiliza el puerto JDK `AsyncObjectStorage` y el cliente S3 nativo. La descarga
transmite buffers con demanda; `content(key)` libera la respuesta al completar, fallar o cancelar.
Quien use `get(key)` debe consumir o cerrar el objeto recibido, aunque no se haya suscrito al cuerpo.

`ReactiveDynamoRepositories` crea fachadas diferidas sobre `DynamoAsyncRepository`. El kit
conserva las condiciones de versión del SDK y cancela la petición de bajo nivel, incluso cuando
el Enhanced Client interpone futuros de mapeo. Las consultas obtienen una sola página acotada;
el cursor permite pedir la siguiente. Los conversores de atributos de este camino deben realizar
solo trabajo de CPU. La autoconfiguración reactiva no crea repositorios ni clientes síncronos.

`ReactiveInbox` utiliza `AsyncInboxStore` con DynamoDB y `ReactiveInboxStore` con R2DBC. Este
último conserva el contexto de la transacción, incluso al cambiar de hilo. Las variantes
síncronas del inbox permanecen en los hilos de poll/purga dedicados a esas tareas; comparten las
reglas de propiedad y lease con las variantes nativas. Deshabilitar inbox evita inicializar
tanto sus recursos nativos como los de poll.

Flyway y el calentamiento de credenciales se ejecutan al arrancar. El poll de Kafka/SQS puede
esperar al handler con timeout para decidir acknowledgement, reintentos y DLQ. El handler y
su I/O mantienen el modelo reactive; esta excepción del hilo de poll no habilita bloqueos en
una petición HTTP.

## Requisitos de las capacidades reactive

Cada capacidad marcada reactive necesita contratos e integración con BlockHound, incluyendo
cancelación, errores, reconexión y renovación de credenciales.
Las pruebas de servicios reactive habilitan la autodetección de extensiones de JUnit:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

`axiz doctor` comprueba esta configuración y los usos bloqueantes. `ReactiveRules` detecta
puertos síncronos, esperas de futuros, SDKs síncronos, `block` y offload prohibido.
El contexto de request y observación viaja en Reactor Context y Micrometer; no se usan hooks
globales de Reactor. El logging predeterminado es asíncrono, con cola acotada y stdout.
En el ejecutor de completado AWS, BlockHound permite únicamente la espera ociosa de `getTask`
del JDK y el cierre del pool de canales Netty; los callbacks mantienen la detección de sleeps y
esperas bloqueantes de colas. DNS, TTL e inactividad de conexiones y timeouts se configuran
explícitamente en el cliente nativo. AWS utiliza un asignador sin el pool adaptativo que puede
estacionar hilos al ampliarse. El proveedor XML, la configuración del JDK, los trust managers TLS
y el SPI de contexto se inicializan al arrancar. La validación de certificados y hostname se
conserva. Las pruebas incluyen handshake en frío, conexiones nuevas con el archivo de confianza
ya retirado, rechazo de certificados/hostname incorrectos y cargas concurrentes con checksum.

`BlockHoundExtension.detectedBlockingCalls()` conserva hasta 32 sitios originales de bloqueo.
Las integraciones nativas exigen que el registro quede vacío, incluso si un driver transforma
el error en otra excepción.

Para PostgreSQL/SCRAM se configura el DRBG estándar con
`-Djava.security.egd=file:/dev/./urandom` y se inicializa al arrancar. Los servicios reactive
validan esta condición; el parent, Jib y Helm generado incluyen la opción. Esta ruta ya se
comprobó con PostgreSQL real y BlockHound; la variante native requiere validación propia.
