<!-- generated -->
# axiz-test-spring-boot-starter

Test support: Testcontainers (PostgreSQL, LocalStack, Kafka, Redis), WireMock, ArchUnit rules, BlockHound and fixtures.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-test-spring-boot-starter</artifactId>
  <scope>test</scope>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizContainers` | Containers matching the approved infrastructure: Aurora PostgreSQL, MSK (Kafka), ElastiCache (Valkey) and LocalStack for DynamoDB, SQS, SNS, S3, KMS, Secrets Manager and Parameter Store. |
| `BlockHoundExtension` | Detects blocking on Reactor, AWS I/O/completion workers and Lettuce I/O/callback workers. |
| `BlockHoundTestExecutionListener` | Installs requested blocking detection before the test plan starts creating scheduler threads. |
| `HttpStubs` | WireMock stub for a logical HTTP client: starts on a random port and points `axiz.http-client.clients.<name>.base-url` at it. |
| `OpenApiResponses` | Checks inline response examples and serialized MockMvc/WebTestClient bodies against the source OpenAPI 3.1 schema, including local references and format assertions. |
| `KafkaTestConfiguration` | Kafka for messaging tests; IAM authentication is switched off for the local broker. |
| `LocalStackTestConfiguration` | LocalStack for the AWS adapters. |
| `PostgresTestConfiguration` | PostgreSQL for `@SpringBootTest`: `@Import(PostgresTestConfiguration.class)`. |
| `RedisTestConfiguration` | Valkey (Redis protocol) for cache, idempotency, locks and rate limits. |
| `RequestContexts` | Opens a request context in unit tests, as the web and security starters would. |

PostgreSQL and Redis fixtures supply the native plaintext options of their local containers.
They never set `axiz.config.environment` or authorize HTTP/insecure stores. Each Boot test must
declare its environment and the permissions needed by its imported fixtures explicitly. Generated
application tests do this in their own `@SpringBootTest` properties. TLS acceptance tests use their
own TLS-enabled backends instead of these plaintext fixtures.

## Kafka authentication selection

When MSK is a dependency, set `axiz.messaging.kafka.authentication=configured` in
`@SpringBootTest(properties = ...)` together with the explicit test transport settings.
This selector must be available while Spring evaluates auto-configuration conditions.
A `DynamicPropertyRegistrar` runs too late to undo an MSK configuration already selected
from application YAML. The Kafka fixture then supplies the broker address and disables IAM;
generated application tests include the early selector automatically.

## Local AWS credentials

`LocalStackTestConfiguration` supplies a context-local `AwsCredentialsProvider` bean together
with both framework and Spring Cloud AWS endpoint/region properties. It does not set JVM credential
properties. Generated audit fixtures register their provider and destinations in the initializer,
before production client auto-configuration; they require no global credential restoration or
JUnit `@Isolated` annotation.

## Original blocking sites

`BlockHoundExtension.detectedBlockingCalls()` returns an immutable snapshot of at most 32
original blocking sites, including thread, method and stack. The extension still throws the
original `BlockingOperationError`. The snapshot also reveals violations swallowed or masked by
a driver's later exception. Assert that it is empty in native I/O acceptance tests; tests that
intentionally exercise blocking detection can inspect the recorded sites instead.

Continue opting in with `@ExtendWith(BlockHoundExtension.class)` (including composed or inherited
class annotations). The JUnit Platform listener discovers that opt-in across the complete test
plan and installs BlockHound before the first test executes. This prevents late instrumentation
from invalidating executor wait frames after an earlier, unannotated test started Reactor.
Servlet-only test plans do not install the optional instrumentation. Keep JUnit's service-loaded
test execution listeners enabled. No application blocking allowances are added by this listener.
