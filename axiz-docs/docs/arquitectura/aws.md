# Infraestructura AWS

axiz integra solo los servicios aprobados. Cada uno tiene un módulo dueño y un equivalente local
para desarrollo y pruebas. Cualquier otro (GCP, Hazelcast, Vault, EventBridge, Cognito, API
Gateway, CodeArtifact, ECR) queda fuera, y el enforcer bloquea sus librerías.

| Servicio | Uso | Módulo axiz | Local / pruebas |
|---|---|---|---|
| Amazon EKS | cómputo de todos los servicios | chart Helm generado | kind |
| Aurora PostgreSQL | base relacional, outbox, inbox, sagas, secuencias | `axiz-kit-jpa` | PostgreSQL (Compose, Testcontainers) |
| Amazon DynamoDB | NoSQL, inbox, idempotencia, locks, secuencias | `axiz-kit-dynamodb` | LocalStack |
| Amazon ElastiCache (Redis/Valkey) | caché L2, idempotencia, rate limit, locks | `axiz-adapter-cache-redis` | Valkey (Compose, Testcontainers) |
| Amazon MSK | eventos (Kafka con autenticación IAM), auditoría | `axiz-adapter-messaging-kafka` | Kafka (Compose, Testcontainers) |
| Amazon SQS | colas estándar y FIFO, DLQ | `axiz-adapter-messaging-sqs` | LocalStack |
| Amazon SNS | pub/sub estándar y FIFO | `axiz-adapter-messaging-sns` | LocalStack |
| AWS Secrets Manager | secretos (sincronizados con External Secrets) | `axiz-adapter-secrets-aws` | LocalStack, `local/secrets` |
| AWS Systems Manager Parameter Store | parámetros compartidos fuera del clúster | `axiz-config-spring-boot-starter` | LocalStack |
| ConfigMaps montados como archivos | configuración base de cada servicio | `axiz-config-spring-boot-starter` | perfil `local`, kind |
| AWS KMS | cifrado de campos (envelope) y de objetos en S3 | `axiz-adapter-crypto-kms` | LocalStack |
| Amazon S3 | archivos, batch, archivo de auditoría | `axiz-adapter-storage-s3` | LocalStack |
| Observabilidad | OpenTelemetry Collector en EKS; Prometheus y Grafana; trazas y logs en el backend OTLP de plataforma | `axiz-observability-*`, `axiz-metrics-*` | Grafana LGTM en Docker |

Fuera de AWS: GitHub Packages (Maven), GHCR (imágenes y charts OCI), GitHub Actions (CI/CD), Argo CD
(despliegue) y el IdP OIDC corporativo.

## Convenciones de los clientes AWS

- Un solo SDK: AWS SDK v2 (el v1 está prohibido). Los clientes síncronos propios se construyen con
  `AwsClients`; los asíncronos nativos, con `AsyncAwsClients`. La observación se compone mediante `axiz-adapter-observability-aws`
  (un span CLIENT por llamada con servicio y operación). La región procede de
  `axiz.aws.region` y el endpoint de `axiz.aws.<servicio>.endpoint` o, si no está,
  `axiz.aws.endpoint` (LocalStack en local). Claves de servicio: `sqs`, `sns`, `s3`, `dynamodb`,
  `kms`, `secretsmanager`, `ssm`. Un bean propio del cliente reemplaza al del framework.
  Los módulos adaptadores AWS traen `axiz-adapter-aws`. El bootstrap de Parameter
  Store pertenece a Axiz (`AxizParameterStoreResolver` y `AxizParameterStoreLoader`). Axiz excluye
  `CredentialsProviderAutoConfiguration` y `AwsAutoConfiguration` de Spring Cloud AWS.
- Sin una declaración IRSA se conserva la cadena predeterminada del SDK, incluido EKS Pod Identity.
  Con IRSA declarada, la política común exige rol y archivo de token completos y usa exclusivamente
  Web Identity con STS. Las credenciales estáticas de sistema/entorno no la sustituyen. Las pruebas
  locales usan proveedores explícitos o no declaran IRSA.
  Si falla IRSA no se continúa hacia perfiles, credenciales de contenedor ni IMDS. El soporte AWS
  incluye el módulo STS en ejecución. Los proveedores personalizados conservan su propia política.
  No se configuran llaves productivas en YAML.
- La misma política se aplica al callback MSK, a los tokens R2DBC y al plugin IAM de JDBC. JDBC
  firma cada conexión física y reconexión usando una fuente compartida por pool Hikari; no instala
  un manejador global del wrapper. Los fallos de conexión y los reemplazos no destruyen esa fuente;
  el pool libera su recurso y Spring cierra la fuente compartida después de sus consumidores. El framework selecciona su perfil de plugins cuando IAM está
  habilitado. Las integraciones de contexto seleccionan `AwsIdentity` por capacidad, servicio,
  proveedor global y fuente SDK predeterminada. El bootstrap propio de Parameter Store valida IRSA
  antes de cargar configuración; una declaración incompleta se rechaza.
- Nombres de tablas, colas y tópicos por convención con prefijo de ambiente
  (`${axiz.dynamodb.table-prefix}axiz-idempotency`), aprovisionados por la infraestructura como
  código de la plataforma, fuera del framework; nunca por el servicio al arrancar.
- MSK con IAM (`axiz.messaging.kafka.iam=true` en los ambientes), SQS FIFO con el id del evento como
  id de deduplicación, S3 con SSE-KMS y URLs prefirmadas con TTL máximo.
  El generador usa IAM por defecto; local, pruebas y kind lo desactivan expresamente para Kafka sin
  IAM. Los clientes MSK standalone requieren la librería IAM y el soporte AWS común además del
  adaptador Kafka.

## Identidades suministradas por la aplicación

Las integraciones inyectan `AwsIdentity`, una infraestructura declarativa y pasiva del contexto.
Las aplicaciones pueden aportar beans singleton `AwsCredentialsProvider`. Un bean sin calificador
es global. `@AwsIdentityFor(service = AwsService.S3)` limita un proveedor a S3;
`@AwsIdentityFor(capability = AwsCapability.AUDIT)` lo limita a auditoría. Se declara exactamente
uno de los dos atributos. La precedencia es capacidad → servicio → global → fuente del contexto.
`@Primary` resuelve varios candidatos dentro del mismo alcance; una ambigüedad, declaración
inválida o proveedor no singleton se rechaza al arrancar, aunque su alcance no esté en uso.
No se exige que las capacidades declaradas estén activas.

Un calificador textual o ajeno a Axiz queda excluido del alcance global y no se interpreta como
selector AWS. Los atributos de anotaciones compuestas deben usar los enums tipados. Un bean
sin calificador y `defaultCandidate=false` no se considera global; los beans con alcance tipado
siguen disponibles. Se heredan candidatos del padre salvo que su nombre quede oculto localmente.
Importar los tipos desde `pe.axiz.spring.aws`.

Por ejemplo, una aplicación que ya posee un cliente STS puede aportar un rol distinto para S3:

```java
@Bean(destroyMethod = "close")
@AwsIdentityFor(service = AwsService.S3)
AwsCredentialsProvider storageIdentity(
    StsClient sts, @Value("${app.aws.storage-role-arn}") String roleArn) {
  return StsAssumeRoleCredentialsProvider.builder()
      .stsClient(sts)
      .refreshRequest(request -> request.roleArn(roleArn).roleSessionName("payments-storage"))
      .asyncCredentialUpdateEnabled(true)
      .build();
}
```

El rol, `ExternalId` cuando corresponda y la configuración del cliente STS pertenecen a la
aplicación y su plataforma. El framework toma una vista prestada del proveedor: conserva endpoint,
timeouts, observación y límites del transporte. Los clientes asíncronos nativos y R2DBC usan
snapshots calentados al inicio y refrescados fuera de los hilos de petición. El propietario del
bean cierra el proveedor; cada capacidad cierra sus propios snapshots, workers y clientes. La vista
prestada invoca `resolveCredentials` y no conserva una implementación particular de
`resolveIdentity` asíncrono del proveedor. La resolución se prepara y refresca fuera de los hilos
de petición en las capacidades asíncronas descritas.

Secrets Manager y Parameter Store tienen propietarios reactivos separados para admitir identidades
distintas. Auditoría SQS mantiene clientes propios sin convertirse en candidatos predeterminados
de mensajería. Para reemplazar directamente sus SDK clients se usan los beans
`axizAuditSqsClient` o `axizAuditSqsAsyncClient`, con `defaultCandidate=false`.
La extensión alcanza también el callback MSK y los pools Hikari del wrapper JDBC. Estos pools se
configuran antes de abrir conexiones; cada reconexión conserva la identidad elegida y firma un
token nuevo, sin manejadores globales de credenciales.

La importación `aws-parameterstore:` sucede antes del contexto Spring y se personaliza mediante
el mecanismo de bootstrap de Spring Cloud AWS. Un proveedor definido como bean no configura esa
fase anterior. Los SDK clients o `ProducerFactory` completos proporcionados por la aplicación
conservan su configuración propia. Sin un bean aplicable, se usa la política predeterminada ya
descrita, incluido el fallo cerrado de IRSA.

Las pruebas LocalStack registran un proveedor local al contexto. Los fixtures de auditoría
generados no escriben credenciales en propiedades globales ni necesitan `@Isolated`.

## Recursos AWS separados e identidad por contexto

La base `axiz-adapter-aws` separa paquetes de identidad y transporte y no depende de Micrometer.
`axiz-adapter-observability-aws` aporta instrumentación mediante el SPI del SDK; Spring compone
ambas partes. Los clientes conservan su transporte, firma, límites y observación. Las métricas
usan únicamente el `MeterRegistry` del contexto; sin registro no se crea vinculación global.

`AwsIdentity.source(AwsService, AwsCapability)` devuelve un recurso `AwsCredentialSource` con cierre uniforme.
El consumidor cierra el recurso o transfiere su propiedad a otro propietario; el SDK recibe
`provider()`, una vista no cerrable. Cerrar un préstamo invalida su vista sin cerrar el proveedor
compartido ni un bean de aplicación. El préstamo no puede sobrevivir al contexto propietario.
La fuente predeterminada es única por contexto y se crea cuando es necesaria; se mantienen las
identidades explícitas y su precedencia. Los clientes, pools y callbacks cierran sus recursos
antes de destruir la identidad del contexto. El bootstrap anterior a Spring mantiene su dueño
separado; esta política no introduce una identidad global por proceso.

El plugin JDBC exige un ámbito válido del framework. Los pools externos con perfiles propios
se conservan intactos; los ámbitos reservados desconocidos fallan cerrados. Esta definición
mantiene la reutilización de sesión, el aislamiento de identidad y el rechazo de IRSA inválida.

## Informe seguro y configuración común de transporte

El gestor registra una entrada INFO por selección real de servicio/capacidad, sin duplicados y
al adquirir recursos perezosos. Informa el origen configurado y el alcance, no el principal ni los
permisos efectivos de IAM. No resuelve credenciales para informar, no consulta AWS y no depende
de auditoría. Los nombres no seguros se ocultan; no se escriben claves, secretos o ARN completos.

`AwsClientSettings` centraliza endpoint y región sin Spring o Micrometer. Un endpoint específico
no vacío tiene prioridad; ausente, vacío o en blanco usa `axiz.aws.endpoint`. Los valores se
recortan antes de construir el URI. S3 usa el mismo endpoint efectivo y estilo de acceso en cliente
y presigner. En imperativo, una región ausente conserva la cadena regional del SDK (`SDK_CHAIN`);
en reactivo, `EXPLICIT` exige `axiz.aws.region` o `AWS_REGION`, sin perfiles ni IMDS como respaldo.
Los timeouts de mensajería síncrona se conservan. `CompletionObserver.NOOP` es el observador común
sin efectos. La metadata nativa STS proviene del SDK; no se duplica en el adaptador de observación.

## Composición de Spring Cloud AWS y TLS

Spring Cloud AWS usa un puente que selecciona solo el global explícito de aplicación o la fuente
predeterminada del contexto. Los proveedores con alcance conservan su alcance, incluso con
`@Primary`; no existe otro proveedor SDK global automático. S3 y DynamoDB usan los adaptadores
de Axiz sin las integraciones redundantes de Spring Cloud AWS.

La configuración `spring.cloud.aws.credentials.*` activa se rechaza antes del acceso remoto:
no reemplaza IRSA por claves estáticas, perfiles o reglas STS. `instance-profile=false` es válido.
Para una identidad explícita se declara un proveedor SDK propio. Parameter Store aplica esta
misma política antes de cargar configuración y respeta extensiones explícitas de su bootstrap.
Su dueño de recursos separado cierra el cliente y después la fuente, también en fallos iniciales.

Los transportes gestionados requieren HTTPS y conservan validación de certificados y hostname.
Solo para emuladores en `local`, `test` o `ci`, habilitar `axiz.transport.allow-plain-http=true`.
La excepción depende del ambiente y no se habilita por ser loopback. Se comprueban las URLs
efectivas, incluidos endpoints del SDK obtenidos de JVM, entorno o perfil. Los clientes HTTP
no siguen redirecciones y no envían credenciales a un origen absoluto diferente.

Las duraciones `axiz.aws.async.connection-ttl` y `max-idle-time` se enlazan como `Duration` (5m y
30s por defecto); admiten `100ms`, `PT0.1S` o `100` milisegundos, siempre positivos y al menos 1ms.
Son independientes de `axiz.aws.completion`. La región IAM específica vacía usa el respaldo global.
