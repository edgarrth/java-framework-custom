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
| `ReactiveStages` | Deferred native stages and streaming publishers with explicitly scoped subscriber context and native cancellation. |
| `AxizFoundationAutoConfiguration` | Clock, business calendar, id generation, identifier registry and Money JSON support. |
| `AxizFoundationProperties` | Foundation settings (`axiz.foundation.*`), normally in the `axiz-common` ConfigMap. |
| `IdentifierConstraintValidator` | Validator of `ValidIdentifier` backed by the identifier registry. |
| `IdentifierValidation` | Registry used by `IdentifierConstraintValidator`. |
| `MoneyJacksonModule` | Jackson module for `Money`: the amount is written as a string so clients never parse it as a binary floating point number. |
| `ValidIdentifier` | Bean Validation constraint for any registered identifier type, e.g. |
| `SchemaInitializer` | Creates framework tables from `axiz/sql/<name>-<dialect>.sql`. |
| `SpringConnectionProvider` | Connection provider bound to Spring transactions: inside a transaction the transaction's connection is returned and left open on release. |
| `SpringTransactionRunner` | Runs work in a new or joined Spring transaction; checked exceptions roll back too. |
| `SqlDialects` | Detects the database product to choose SQL dialects (PostgreSQL in clusters, H2 in tests). |

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
