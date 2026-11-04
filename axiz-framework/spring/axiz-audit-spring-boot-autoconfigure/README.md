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

## Native reactive audit

Reactive applications expose `ReactiveAuditTrail` and `ReactiveAuditPublisher`, backed by
`AsyncAuditEventPublisher`/`AsyncAuditSink`. SQS uses `SqsAsyncClient`, startup-owned AWS resources,
and exact native cancellation. Imperative sinks are rejected; Kafka audit uses the dedicated bounded asynchronous submission
boundary described below in reactive applications.
The global `axiz.audit.enabled=false` switch also disables native wiring.

`@Auditable` supports Mono/Flux methods in reactive applications. Work and record construction are
deferred; each subscription has its own event, arguments and request metadata. Success waits for
delivery; a delivery failure propagates. Business failures remain the original error, with a delivery
failure attached as suppressed; cancellation records one failure unless terminal delivery already
started, in which case it cancels the active send. The HTTP filter composes the same lifecycle.

With `axiz.audit.durable.enabled=true`, successful records use `ReactiveOutboxWriter` in the
subscriber's R2DBC transaction. The audit advisor runs at order 60, inside the hexagonal use-case
transaction advisor (50). If configuring a separate Spring transaction advisor, order it before
native audit as well. No CompletionStage conversion crosses the R2DBC transaction. Failures use
the native fallback, since their business transaction rolls back. Outside a transaction, `fail`
rejects success delivery and `async` uses the fallback. Log-only delivery is best effort and uses
the framework's non-blocking reactive appender; it is not an outbox durability guarantee.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-audit-spring-boot-autoconfigure verify`.

### Kafka submission in reactive applications

Servlet publication keeps its synchronous adapter. In reactive applications the framework invokes
`KafkaTemplate.send()` on dedicated workers because metadata lookup or a full producer buffer can
wait inside `send()`. Completion means broker acknowledgement; there is no wait for that ack on a
worker or Reactor thread. The standard Kafka client is retained.

`axiz.audit.kafka.send-workers` defaults to 2 and `send-queue-capacity` to 256. Admission is
bounded across queued work and unacknowledged sends; exhaustion fails immediately rather than
running on the caller. The existing Kafka timeout bounds submission plus acknowledgement and
`max.block.ms`. Metrics `axiz.transport.pending`, `.queued`, `.rejected`, and `.latency` carry the
`transport` tag (`kafka-events` or `kafka-audit`). Producers and workers close with the context.

Cancelling queued work prevents its send. Once Kafka has accepted a send, cancellation or timeout
cannot retract it: a late acknowledgement is possible, and capacity remains reserved until the
transport completes. Retrying must preserve the event ID and use consumer inbox deduplication.
