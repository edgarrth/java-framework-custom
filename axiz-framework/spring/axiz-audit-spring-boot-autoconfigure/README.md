<!-- generated -->
# axiz-audit-spring-boot-autoconfigure

@Auditable, sanitized audit events and asynchronous publishers (log, MSK, SQS) (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-audit-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AuditContextResolver` | Builds the audit context (actor, correlation, channel, tenant) from the current request. |
| `AuditOutsideTransaction` | What durable audit does with a successful action recorded outside a transaction. |
| `AuditSink` | A destination of audit events contributed by an adapter auto-configuration. |
| `AuditableInterceptor` | Applies `Auditable`: records who did what on which resource and with what result. |
| `AxizAuditAutoConfiguration` | Audit: `Auditable` through an infrastructure advisor, sanitized immutable events, real asynchronous publication (bounded queue, virtual thread, log fallback) and optional HTTP audit. |
| `AxizAuditKafkaAutoConfiguration` | MSK sink for audit events, active when `axiz.audit.kafka.topic` is set. |
| `AxizAuditProperties` | Audit settings (`axiz.audit.*`). |
| `AxizAuditSqsAutoConfiguration` | SQS sink for audit events, active when `axiz.audit.sqs.queue-url` is set. |
| `HttpAuditFilter` | Audits mutating HTTP requests. |
| `HttpAuditWebFilter` | Reactive counterpart of `HttpAuditFilter`. |
| `TransactionalAuditPublisher` | Durable audit: a successful action recorded inside a transaction is written to the outbox in that same transaction, so the evidence exists if and only if the business change was committed, and survives a crash right after the commit (the relay publishes it later, at least once, with the event id for deduplication). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-audit-spring-boot-autoconfigure verify`.
