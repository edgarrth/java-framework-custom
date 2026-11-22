# Provider recipes

Executable consumer examples for provider choices not exercised by the payment flow.
Requires JDK 25, Docker and the installed Axiz artifacts. Uses only local containers.

```bash
./mvnw -pl examples/provider-recipes verify
```

Run this command from `axiz-demo`. Each test starts only the selected Spring configurations
from the public starters declared in this POM. An application should depend on the required
subset of starters, never on this example module. Tests own provisioning and resource cleanup.

The relational recipes use PostgreSQL, including rollback, duplicate delivery, sequence
reservation and competing scheduler locks. SQL migrations are applied before consumers start;
production deployments should manage them with their normal migration process.

| Recipe | What the consumer does | Boundary exercised |
|---|---|---|
| `RelationalRecipesTest` | Reserves JDBC/R2DBC sequence values, rolls back business work, deduplicates inbox deliveries, commits/rolls back outbox events with business rows, excludes competing scheduled workers | PostgreSQL through public provider configurations |
| `CloudRecipesTest` | Uploads/downloads/deletes S3 content, shares DynamoDB sequence counters, coordinates inbox/idempotency/locks, resolves Secrets Manager/SSM values, loads Parameter Store before beans exist | LocalStack; synchronous and native asynchronous providers |
| `RedisRecipesTest` | Caches/evicts data, fences locks and idempotent responses, shares rate limits | Valkey through Redis providers on both stacks; also verifies missing/disabled providers cannot claim coverage |
| `MessagingRecipesTest` | Publishes through the SQS and SNS messaging providers and sends sanitized audit records to independent SQS/Kafka destinations | LocalStack and Kafka; real producer/consumer delivery on both stacks |
| `IdentityRecipesTest` | Selects a credential identity, signs expiring RDS authentication, configures a pool-scoped IAM profile and MSK TLS/SASL callback, reads mounted secrets | Local identity contracts and real filesystem I/O; no remote RDS or MSK authentication |
| `CrossCuttingRecipesTest` | Logs operations with excluded arguments/results, observes outcomes without secret attributes, resolves localized messages, propagates and clears trusted tenant context | In-process Spring consumer contracts; JWT validation and OTLP collection are separate boundaries |
| `OutboundRecipesTest` | Retries an HTTP read, refuses to replay an unkeyed payment, calls SOAP after credential rotation | Real loopback HTTP/XML transports; production requires TLS |
| `RemoteTokenClientRecipeTest` | Tokenizes, looks up, describes, reveals and deletes through `Tokenizer`; uses subscriber identity with `ReactiveTokenizer` | Remote-client protocol contract against a loopback fixture, not a deployable token vault |

To run one recipe, use `./mvnw -pl examples/provider-recipes -Dtest=RedisRecipesTest test`.
Read the named test for imports, selected starter configuration, properties, provisioning and
resource ownership. Use only the starters needed by your service. The examples intentionally
keep provider selection explicit so a service never falls back silently to an in-memory store.

The token client returns an opaque token and allowed metadata. Search uses the vault's lookup
operation; ciphertext and the equality index remain in the vault. Token relay uses the validated
request identity, or the Reactor subscriber's identity. A process without an incoming identity
uses client credentials instead. Automatic token retries are disabled; any business retry must
use its stable operation identifier and the vault's documented idempotency contract.

Local identity tests cannot establish AWS IAM enforcement. They check configuration and signing
without a remote AWS account. LocalStack exercises the AWS API contracts used by the framework;
production still needs service-owned IAM, TLS and infrastructure. No test prints secret material.

The demo's `coverage` module reads actual Surefire and Failsafe results after all consumer modules execute.
It rejects missing, failed, skipped or stale evidence and requires every persistence choice in
the framework catalog. `coverage.yaml` records the test and the level of evidence for every
capability; `variants.yaml` adds explicit stack/provider obligations. These are demo acceptance
checks, separate from the framework's own unit, integration and architecture tests.

The notification recipe invokes `NotificationSender` and `ReactiveNotifications`, then verifies the
actual `notification.requested.v1` envelopes received through SNS -> SQS. The delivery application
owns email/SMS/push providers; Axiz publishes the request, correlation and template parameters.

Las recetas comunes se ejecutan en contextos servlet y reactivos. `PolicyRecipesTest` muestra
enmascaramiento, feature flags por canal y métricas; `CloudRecipesTest` realiza CRUD y paginación
DynamoDB con cursores ligados a la consulta, incluyendo rechazo de otra partición.
`MessagingRecipesTest` verifica también el publicador Kafka servlet con confirmación y lectura
real del broker. El consumidor SQS, sus DLQ y FIFO están en el ejemplo mínimo
[`sqs-consumer`](../sqs-consumer/README.md), que usa descubrimiento automático de starters.
