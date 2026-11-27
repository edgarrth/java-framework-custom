<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-test-spring-boot-starter

Soporte de pruebas: Testcontainers (PostgreSQL, LocalStack, Kafka, Redis), WireMock, reglas ArchUnit, BlockHound y fixtures.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-test-spring-boot-starter</artifactId>
  <scope>test</scope>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AxizContainers` | Contenedores que corresponden a la infraestructura aprobada: Aurora PostgreSQL, MSK (Kafka), ElastiCache (Valkey) y LocalStack para DynamoDB, SQS, SNS, S3, KMS, Secrets Manager y Parameter Store. |
| `BlockHoundExtension` | Detecta bloqueos en Reactor, en los workers de I/O y de finalización de AWS y en los workers de I/O y de callbacks de Lettuce. |
| `BlockHoundTestExecutionListener` | Instala la detección de bloqueos solicitada antes de que el plan de pruebas empiece a crear hilos de scheduler. |
| `HttpStubs` | Stub de WireMock para un cliente HTTP lógico: arranca en un puerto aleatorio y apunta `axiz.http-client.clients.<name>.base-url` hacia él. |
| `OpenApiResponses` | Comprueba los ejemplos de respuesta en línea y los cuerpos serializados de MockMvc/WebTestClient contra el esquema OpenAPI 3.1 de origen, incluidas las referencias locales y las aserciones de formato. |
| `KafkaTestConfiguration` | Kafka para pruebas de mensajería; la autenticación IAM se desactiva para el broker local. |
| `LocalStackTestConfiguration` | LocalStack para los adaptadores AWS. |
| `PostgresTestConfiguration` | PostgreSQL para `@SpringBootTest`: `@Import(PostgresTestConfiguration.class)`. |
| `RedisTestConfiguration` | Valkey (protocolo Redis) para caché, idempotencia, locks y límites de tasa. |
| `RequestContexts` | Abre un contexto de petición en pruebas unitarias, como lo harían los starters web y de seguridad. |

Los fixtures de PostgreSQL y Redis aportan las opciones nativas de texto plano de sus contenedores
locales. Nunca definen `axiz.config.environment` ni autorizan HTTP o almacenes inseguros. Cada prueba de
Boot debe declarar explícitamente su entorno y los permisos que necesitan los fixtures que importa. Las
pruebas de las aplicaciones generadas lo hacen en sus propias propiedades de `@SpringBootTest`. Las
pruebas de aceptación TLS usan sus propios backends con TLS habilitado en lugar de estos fixtures de
texto plano.

## Selección de la autenticación Kafka

Cuando MSK es una dependencia, se define `axiz.messaging.kafka.authentication=configured` en
`@SpringBootTest(properties = ...)` junto con la configuración explícita de transporte de prueba.
Este selector debe estar disponible mientras Spring evalúa las condiciones de autoconfiguración.
Un `DynamicPropertyRegistrar` se ejecuta demasiado tarde para deshacer una configuración MSK ya
seleccionada desde el YAML de la aplicación. Luego el fixture de Kafka aporta la dirección del broker y
desactiva IAM; las pruebas de las aplicaciones generadas incluyen el selector temprano automáticamente.

## Credenciales AWS locales

`LocalStackTestConfiguration` aporta un bean `AwsCredentialsProvider` local al contexto junto con las
propiedades de endpoint y región tanto del framework como de Spring Cloud AWS. No define propiedades de
credenciales de la JVM. Los fixtures de auditoría generados registran su proveedor y sus destinos en el
inicializador, antes de la autoconfiguración de los clientes de producción; no requieren restaurar
credenciales globales ni la anotación `@Isolated` de JUnit.

## Sitios de bloqueo originales

`BlockHoundExtension.detectedBlockingCalls()` devuelve una instantánea inmutable de como máximo 32 sitios
de bloqueo originales, con hilo, método y pila. La extensión sigue lanzando el `BlockingOperationError`
original. La instantánea también revela violaciones que una excepción posterior del driver se trague o
enmascare. En las pruebas de aceptación de I/O nativa se verifica que esté vacía; las pruebas que ejercitan
intencionalmente la detección de bloqueos pueden inspeccionar en su lugar los sitios registrados.

La activación se declara con `@ExtendWith(BlockHoundExtension.class)` (incluidas las anotaciones de clase
compuestas o heredadas). El listener de JUnit Platform descubre esa activación en todo el plan de pruebas
e instala BlockHound antes de que se ejecute la primera prueba. Esto evita que una instrumentación tardía
invalide los frames de espera de los executors después de que una prueba anterior sin anotar haya
iniciado Reactor. Los planes de prueba solo servlet no instalan la instrumentación opcional. Los listeners
de ejecución de pruebas que JUnit carga como servicio deben permanecer habilitados. Este listener no agrega
permisos de bloqueo para la aplicación.
