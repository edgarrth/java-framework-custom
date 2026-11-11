<!-- generated -->
# axiz-r2dbc-spring-boot-autoconfigure

Aurora PostgreSQL for reactive services over R2DBC: IAM tokens, pool defaults, Flyway over JDBC, transactional ReactiveSql, auditing, encrypted and money columns, reader endpoint (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-r2dbc-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AuditableRow` | Base of Spring Data R2DBC rows: time-ordered UUIDv7 id assigned on creation, optimistic locking and auditing columns filled from the caller of the request (`id`, `version`, `created_at`, `created_by`, `updated_at`, `updated_by`). |
| `AxizR2dbcAuditingAutoConfiguration` | Auditing of `AuditableRow` (and any entity with `@CreatedBy`/`@CreatedDate` fields): the caller of the request and the application clock, in UTC. |
| `AxizR2dbcAutoConfiguration` | Reactive relational persistence on Aurora PostgreSQL: a `ReactiveSql` that joins the reactive transaction of the caller, the axiz column converters (`EncryptedText`, `Money`) for Spring Data R2DBC, and an optional reader endpoint. |
| `AxizR2dbcConverters` | Column converters of axiz types for Spring Data R2DBC: `EncryptedText` is stored as AES-256-GCM ciphertext (an explicit entity.field or purpose:name context, shared with JPA only when the application declares that purpose) and `Money` as its text form (`PEN 150.00`). |
| `AxizR2dbcEnvironmentPostProcessor` | R2DBC defaults (lowest precedence) suited to Aurora: connections are validated against the server when borrowed and renewed every 10 minutes, so a failover or an expired IAM token never leaves dead connections in the pool; acquiring a connection is bounded so an exhausted pool fails fast instead of queueing requests forever. |
| `AxizR2dbcFlywayIamAutoConfiguration` | With IAM authentication, Flyway connects over JDBC with a token of its own. |
| `AxizR2dbcIamAutoConfiguration` | IAM database authentication for Aurora (`axiz.r2dbc.iam.enabled=true`): every new connection of the pool, and the Flyway migration at startup, authenticate with a short-lived token signed with the pod's credentials. |
| `AxizR2dbcProperties` | Aurora PostgreSQL over R2DBC (`axiz.r2dbc.*`). |
| `DatabaseClientReactiveSql` | `ReactiveSql` over Spring's `DatabaseClient`: statements join the reactive transaction of the caller (`@Transactional` or `TransactionalOperator`) and are observed by the R2DBC instrumentation. |
| `FlywayIamCustomizer` | Gives Flyway a JDBC connection authenticated with an IAM token (the migration runs once at startup, well within the token's lifetime). |
| `ReaderDatabase` | The reader endpoint of the Aurora cluster (`axiz.r2dbc.reader.url`): a separate pool for queries that tolerate replica lag. |
| `RequestReactiveAuditor` | Auditor of `createdBy`/`updatedBy` for reactive code: the end user, or the calling application for machine-to-machine requests, read from the request metadata that the web and security starters put in the Reactor context (or the thread-local of a consumer thread); `system` otherwise. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-r2dbc-spring-boot-autoconfigure verify`.

## Optional IAM adapter

Services enabling `axiz.r2dbc.iam.enabled` declare `axiz-adapter-database-auth-aws` with `runtime`
scope, or supply their own `DatabasePasswordProvider` for the primary pool. The generated R2DBC
capability includes the AWS adapter. The generic starter and this auto-configuration do not force
it onto minimal consumers.
An enabled IAM configuration without its adapter or a custom provider fails with an actionable
message. IAM reader pools use separate host-specific token providers and require the adapter.
Ordinary password authentication, including password-based reader pools, works without it.
