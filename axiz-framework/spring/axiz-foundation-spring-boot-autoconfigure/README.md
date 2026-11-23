<!-- generated -->
# axiz-foundation-spring-boot-autoconfigure

Dinero, tiempo y calendario de negocio, identificadores y validación de documentos para Spring Boot (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-foundation-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ReactiveInfrastructureBootstrap` | Prepara el DRBG y el descubrimiento opcional de SPI de contexto antes de las suscripciones reactivas. |
| `BoundedAsyncDispatcher` | Admisión de transporte acotada, plazos de confirmación, cancelación y métricas para los workers de envío a Kafka. |
| `ReactiveStages` | Etapas nativas diferidas y publicadores de streaming con contexto del suscriptor de alcance explícito y cancelación nativa. |
| `AxizFoundationAutoConfiguration` | Reloj, calendario de negocio, generación de ids, registro de identificadores y soporte JSON de Money. |
| `AxizFoundationProperties` | Configuración base (`axiz.foundation.*`), normalmente en el ConfigMap `axiz-common`. |
| `IdentifierConstraintValidator` | Validador de `ValidIdentifier` respaldado por el registro de identificadores. |
| `MoneyJacksonModule` | Módulo Jackson para `Money`: el monto se escribe como cadena para que los clientes nunca lo interpreten como número binario de punto flotante. |
| `ValidIdentifier` | Restricción de Bean Validation para cualquier tipo de identificador registrado. |

La integración JDBC (`SpringConnectionProvider`, `SpringTransactionRunner`, `SchemaInitializer`,
`SqlDialects`) está en `axiz-jdbc-spring-boot-autoconfigure`; los puertos de completitud del inbox que
usan los handlers de eventos transaccionales están en `axiz-messaging-spring-boot-autoconfigure`.

## Extensión de identidad AWS

Las integraciones AWS inyectan el bean declarativo `foundation.aws.AwsIdentity`, propiedad del contexto.
Selecciona beans singleton opcionales `AwsCredentialsProvider` del SDK mediante scopes tipados:

```java
@Bean(destroyMethod = "close")
@AwsIdentityFor(service = AwsService.S3)
StsAssumeRoleCredentialsProvider storageIdentity(
    StsClient sts, @Value("${app.aws.storage-role-arn}") String roleArn) {
  return StsAssumeRoleCredentialsProvider.builder()
      .stsClient(sts)
      .refreshRequest(request -> request.roleArn(roleArn).roleSessionName("storage"))
      .asyncCredentialUpdateEnabled(true)
      .build();
}
```

`AwsIdentityFor`, `AwsService` y `AwsCapability` se importan desde `pe.axiz.spring.aws`.
La aplicación configura el cliente STS y los permisos del rol. Se declara exactamente uno de
`service` o `capability`: `@AwsIdentityFor(capability = AwsCapability.AUDIT)` apunta a auditoría,
por ejemplo. El orden de selección es: capacidad, servicio AWS, bean global sin calificar y, por
último, la fuente SDK del contexto. Un único `@Primary` resuelve varios beans dentro de un scope. Las declaraciones
inválidas, los scopes ambiguos y los proveedores no singleton hacen fallar el arranque, incluidas las
declaraciones para scopes sin uso. Un bean `defaultCandidate=false` sin calificar se excluye del scope
global; un bean con scope tipado sigue siendo elegible. Los candidatos del contexto padre se heredan,
salvo que su nombre de bean esté sombreado.

Los calificadores textuales o ajenos de la aplicación se excluyen de la selección global y nunca se
interpretan como scopes AWS. Las anotaciones compuestas pueden usar alias de los atributos de la anotación
tipada. Un proveedor se presta mediante una vista de `resolveCredentials` que no lo cierra; una
implementación personalizada de `resolveIdentity` no evita la política de instantáneas. Los proveedores
de la aplicación siguen siendo propiedad de Spring. Los clientes asíncronos y R2DBC precalientan y
renuevan las instantáneas fuera de los hilos de petición. Cada capacidad es dueña de sus clientes,
workers y recursos de credenciales, y los cierra.

El gestor es infraestructura pasiva. Crearlo nunca crea fuentes del SDK ni proveedores de la aplicación;
la fuente por defecto se comparte de forma diferida dentro del contexto. Los consumidores inyectan este
gestor, sin parámetros de bean factory, definiciones de beans en tiempo de ejecución ni APIs de ciclo de
vida por nombre de consumidor. Una entrada INFO informa la fuente configurada para cada par
servicio/capacidad seleccionado, una sola vez y de forma diferida. No contiene credenciales ni el ARN
completo y no hace llamadas a AWS para identificar al principal. La base sigue siendo usable sin
dependencias de AWS. Los clientes de config-data o bootstrap creados antes del contexto de la aplicación
usan la extensión de bootstrap de Spring Cloud AWS. Un cliente SDK completo provisto por la aplicación
conserva la configuración de identidad de su dueño.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-foundation-spring-boot-autoconfigure verify`.

Los servicios reactivos siembran el DRBG estándar del JDK durante el bootstrap, antes de que los clientes
se autentiquen o el código de las peticiones genere valores aleatorios seguros. En Linux la JVM se inicia
con `-Djava.security.egd=file:/dev/./urandom`. El parent de servicios lo define para pruebas, Jib y
`spring-boot:run`; los valores de Helm generados también lo incluyen. El arranque reactivo falla
explícitamente si el `SecureRandom` por defecto es de otro algoritmo. El framework no instala un
proveedor de seguridad personalizado ni debilita la fuente de entropía. Las pruebas de reconexión SCRAM
se ejecutan con BlockHound habilitado.

## Arranque reactivo

`ReactiveInfrastructureBootstrap.initialize()` siembra el DRBG del JDK e inicializa el descubrimiento
opcional de SPI de contexto de Micrometer en el hilo de arranque. La autoconfiguración reactiva lo invoca
antes de crear los beans de la aplicación. Esto evita que una primera suscripción escanee archivos JAR en
un worker de red. `ReactiveRandomBootstrap` sigue disponible como ayudante de entropía más acotado.

`ReactiveStages.from(...)` conecta un puerto core `CompletionStage` con `Mono`.
`ReactiveStages.flux(...)` delimita el envío de streaming, las señales, la demanda y la cancelación. El
contexto de Reactor es la referencia: cada suscriptor conserva su propio contexto aunque varios llamadores
compartan una misma petición nativa. La composición de etapas core captura los callbacks mediante una SPI
con scope solo del JDK; core no depende de Reactor ni de Micrometer. Los scopes se abren y cierran en el
mismo hilo de ejecución y restauran el contexto previo del worker. No se instala ningún hook global de
Reactor. La propagación de contexto sigue siendo opcional; las etapas nativas también funcionan cuando
su JAR no está presente.

## Propiedad de los recursos AWS

`AwsIdentity.source(AwsService, AwsCapability)` devuelve `AwsCredentialSource`: los consumidores siempre
la cierran o la transfieren al dueño de su recurso. `.provider()` es una vista del SDK que no cierra,
inválida después del cierre del recurso o del contexto. `factory(...)` entrega un recurso independiente
por invocación; `configure(...)` transfiere la propiedad a un plugin del cliente SDK.
`AwsAsyncResources.create` compone la identidad, el transporte nativo y el registro opcional del contexto.
Sin registro no hay vinculación de métricas.

El grafo de dependencias directas de Spring cierra a los consumidores antes que al gestor de identidad.
Cuando se selecciona un proveedor diferido de la aplicación, el gestor registra internamente esa
dependencia real del proveedor, de modo que también sobrevive a la limpieza de pools fallidos. No escanea
beans ajenos en creación.

## Ciclo de vida de workers y readiness

La purga del inbox, el relay del outbox y los consumidores SQS exponen instantáneas inmutables de
sus workers. Con el health de Boot presente, `axizWorkers` está en DOWN para grupos fallidos o detenidos,
o cuando faltan workers; los fallos no capturados de un worker se registran por tipo, sin mensajes
crudos, y detienen el resto del grupo. El grupo de readiness por defecto incluye
`readinessState,axizWorkers`. Las aplicaciones que redefinen los miembros del grupo deben conservar
`axizWorkers` para rechazar tráfico cuando un worker requerido no está disponible.

El outbox y SQS drenan durante 15s por defecto. El arranque rechaza un periodo de gracia de worker que,
sumado al margen de terminación de 5s, supere `spring.lifecycle.timeout-per-shutdown-phase` (servicios
generados: 20s). Los charts generados permiten 90s para fases de cierre secuenciales; el presupuesto del
pod se ajusta al agregar fases, hooks o periodos de gracia mayores. La I/O que no coopera nunca recibe un
callback de detención falso.

### Operación de workers fallidos

`axizWorkers=DOWN` retira readiness; no reinicia por sí solo un pod. Se configura una alerta de
readiness/estado del worker y un procedimiento de diagnóstico y reemplazo del pod. Antes de
repetir una operación externa, su resultado se concilia mediante el mecanismo del proveedor.
Un worker terminado conserva `FAILED` para health y rechaza reinicio en la misma instancia.
Si todos los workers y su cleanup terminaron, `stop(callback)` sí confirma la parada y
`isRunning()` devuelve false, sin esperar innecesariamente el timeout de fase de Spring.
Si el cleanup falla o queda I/O vivo, no se confirma la parada ni se habilita un nuevo worker.

`ReactiveRenderContext` delimita el trabajo síncrono de presentación a la petición de Reactor y la
Observation explícitas, con restauración del MDC y del estado ligado al hilo. Los codecs opcionales de
Netty se inicializan antes del tráfico: se permite que falte una clase opcional, pero una clase instalada
incompatible hace fallar el arranque con su error de enlace en lugar de diferir a una petición un fallo
de classpath inválido.
