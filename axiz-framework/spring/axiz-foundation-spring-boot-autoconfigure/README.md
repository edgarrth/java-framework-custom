<!-- generated -->
# axiz-foundation-spring-boot-autoconfigure

Money, time and business calendar, ids and identifier validation for Spring Boot (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-foundation-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ReactiveInfrastructureBootstrap` | Prepares DRBG and optional context SPI discovery before reactive subscriptions. |
| `BoundedAsyncDispatcher` | Bounded transport admission, ack deadlines, cancellation and metrics for Kafka submission workers. |
| `ReactiveStages` | Deferred native stages and streaming publishers with explicitly scoped subscriber context and native cancellation. |
| `AxizFoundationAutoConfiguration` | Clock, business calendar, id generation, identifier registry and Money JSON support. |
| `AxizFoundationProperties` | Foundation settings (`axiz.foundation.*`), normally in the `axiz-common` ConfigMap. |
| `IdentifierConstraintValidator` | Validator of `ValidIdentifier` backed by the identifier registry. |
| `MoneyJacksonModule` | Jackson module for `Money`: the amount is written as a string so clients never parse it as a binary floating point number. |
| `ValidIdentifier` | Bean Validation constraint for any registered identifier type, e.g. |

JDBC integration (`SpringConnectionProvider`, `SpringTransactionRunner`, `SchemaInitializer`,
`SqlDialects`) lives in `axiz-jdbc-spring-boot-autoconfigure`; the inbox completion ports used by
transactional event handlers live in `axiz-messaging-spring-boot-autoconfigure`.

## AWS identity extension

AWS integrations inject the declarative, context-owned `foundation.aws.AwsIdentity` bean.
It selects optional singleton SDK `AwsCredentialsProvider` beans using typed scopes:

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

Import `AwsIdentityFor`, `AwsService` and `AwsCapability` from
`pe.axiz.spring.aws`. The application configures the STS client and role permissions.
Declare exactly one of `service` or `capability`:
`@AwsIdentityFor(capability = AwsCapability.AUDIT)` targets audit, for example.
Selection order is capability -> AWS service -> unqualified global bean -> context SDK source.
A unique `@Primary` resolves multiple beans within one scope. Invalid declarations, ambiguous
scopes and non-singleton providers fail startup, including declarations for unused scopes.
An unqualified `defaultCandidate=false` bean is excluded from the global scope; a typed scoped
bean remains eligible. Parent candidates are inherited unless their bean name is shadowed.

Textual or unrelated application qualifiers are excluded from global selection and are never
interpreted as AWS scopes. Composed annotations may alias the typed annotation attributes.
A provider is borrowed through a non-closing view of `resolveCredentials`; a custom
`resolveIdentity` implementation does not bypass the snapshot policy. Application providers
remain Spring-owned. Asynchronous clients and R2DBC warm and refresh snapshots outside request
threads. Each capability owns and closes its clients, workers and credential resources.

The manager is passive infrastructure. Creating it never creates SDK sources or application
providers; the default source is shared lazily within the context. Consumers inject this manager,
without bean-factory parameters, runtime bean definitions or consumer-name lifecycle APIs.
An INFO entry reports the configured source for each selected service/capability pair, once and
lazily. It contains no credentials or full ARN and makes no AWS calls to identify the principal.
Foundation remains usable without AWS dependencies. Config-data/bootstrap clients created before
the application context use Spring Cloud AWS's bootstrap extension instead. A complete SDK client
supplied by the application retains its owner's identity configuration.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-foundation-spring-boot-autoconfigure verify`.

Reactive services seed the standard JDK DRBG during bootstrap, before clients authenticate or
request code creates secure random values. On Linux start the JVM with
`-Djava.security.egd=file:/dev/./urandom`. The service parent sets this for tests, Jib and
`spring-boot:run`; generated Helm values also include it. Reactive startup fails explicitly if the
default `SecureRandom` is a different algorithm. The framework does not install a custom security
provider or weaken the entropy source. SCRAM reconnection tests run with BlockHound enabled.

## Reactive startup

`ReactiveInfrastructureBootstrap.initialize()` seeds the JDK DRBG and initializes optional
Micrometer context SPI discovery on the startup thread. Reactive auto-configuration invokes it
before creating application beans. This prevents a first subscription from scanning JAR files
on a network worker. `ReactiveRandomBootstrap` remains available as the narrower entropy helper.

`ReactiveStages.from(...)` bridges a core `CompletionStage` port to `Mono`.
`ReactiveStages.flux(...)` scopes streaming submission, signals, demand and cancellation. Reactor
context is authoritative: each subscriber retains its own context even when several callers share
one native request. Core stage composition captures callbacks through a JDK-only scoped SPI;
core has no dependency on Reactor or Micrometer. Scopes open and close on the same executing
thread, restoring the worker's previous context. No global Reactor hook is installed.
Context propagation remains optional; native stages also work when its JAR is physically absent.

## AWS resource ownership

`AwsIdentity.source(AwsService, AwsCapability)` returns `AwsCredentialSource`: consumers always
close it or transfer it to their resource owner. `.provider()` is a non-closing SDK view, invalid
after resource/context closure. `factory(...)` supplies an independent resource per invocation;
`configure(...)` transfers ownership to an SDK client plugin. `AwsAsyncResources.create` composes
identity, native transport and the optional context registry. No registry means no metric binding.

Spring's direct dependency graph closes consumers before the identity manager. When a lazy
application provider is selected, the manager records that actual provider dependency internally,
so it also survives cleanup of failed pools. It does not scan unrelated beans in creation.

## Worker lifecycle and readiness

Inbox purging, outbox relaying and SQS consumers expose immutable worker snapshots.
With Boot health present, `axizWorkers` is DOWN for failed/stopped groups or missing workers;
uncaught worker failures are logged by type without raw messages, and stop the remaining group.
The default readiness group includes `readinessState,axizWorkers`. Applications overriding group
membership must retain `axizWorkers` to reject traffic when a required worker is unavailable.

Outbox and SQS drain for 15s by default. Startup rejects a worker grace plus the 5s termination
margin that exceeds `spring.lifecycle.timeout-per-shutdown-phase` (generated services: 20s).
Generated charts allow 90s for sequential shutdown phases; adjust the pod budget when adding
phases, hooks or larger grace periods. Uncooperative I/O never receives a false stopped callback.

### Operación de workers fallidos

`axizWorkers=DOWN` retira readiness; no reinicia por sí solo un pod. Configure una alerta de
readiness/estado del worker y un procedimiento de diagnóstico y reemplazo del pod. Antes de
repetir una operación externa, concilie su resultado mediante el mecanismo del proveedor.
Un worker terminado conserva `FAILED` para health y rechaza reinicio en la misma instancia.
Si todos los workers y su cleanup terminaron, `stop(callback)` sí confirma la parada y
`isRunning()` devuelve false, sin esperar innecesariamente el timeout de fase de Spring.
Si el cleanup falla o queda I/O vivo, no se confirma la parada ni se habilita un nuevo worker.

`ReactiveRenderContext` scopes synchronous presentation work to the explicit Reactor request and
Observation, with MDC and thread-local restoration. Optional Netty codecs are initialized before
traffic: a missing optional class is allowed, but an installed incompatible class fails startup
with its linkage error instead of deferring an invalid classpath failure to a request.
