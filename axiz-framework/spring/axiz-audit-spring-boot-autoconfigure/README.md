<!-- generated -->
# axiz-audit-spring-boot-autoconfigure

`axiz.audit.mode=ASYNC|DURABLE|SYNC` is the only delivery selector; boolean selectors are
invalid configuration. Framework-owned publishers require an explicit mode outside local/test.
DURABLE requires a compatible outbox, transaction resource and relay destination; a writer-only
instance explicitly disables its local relay. Empty configured Kafka topics or SQS queue URLs fail
startup. Route support validates addressing, not remote resource existence or permissions.

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
| `AxizAuditProperties` | Audit settings (`axiz.audit.*`). |
| `HttpAuditFilter` | Audits mutating HTTP requests. |
| `HttpAuditWebFilter` | Reactive counterpart of `HttpAuditFilter`. |
| `TransactionalAuditPublisher` | Durable audit: a successful action recorded inside a transaction is written to the outbox in that same transaction, so the evidence exists if and only if the business change was committed, and survives a crash right after the commit (the relay publishes it later, at least once, with the event id for deduplication). |

## Native reactive audit

Reactive applications expose `ReactiveAuditTrail` and `ReactiveAuditPublisher`, backed by
`AsyncAuditEventPublisher`/`AsyncAuditSink`. SQS uses `SqsAsyncClient`, startup-owned AWS resources,
and exact native cancellation. Imperative sinks are rejected; Kafka audit uses the dedicated bounded asynchronous submission
boundary described below in reactive applications.
The global `axiz.audit.enabled=false` switch also disables native wiring.

`@Auditable` supports Mono/Flux methods in reactive applications. Each subscription owns its
record and request metadata. Native R2DBC transaction context is preserved throughout publication.

## Delivery modes and transaction evidence

Set `axiz.audit.mode` explicitly to `async`, `durable` or `sync`. Legacy `durable.enabled`
and `async.enabled` selectors are rejected. Select the Kafka or SQS provider starter separately;
this neutral module does not own those SDKs or auto-configurations.

| Mode | Guarantee |
|---|---|
| `async` (implicit only in development) | Bounded in-memory queue. Enqueue is not a destination acknowledgement and does not survive a process crash. Deployed applications must choose a mode explicitly. |
| `durable` | Success is appended to the outbox in the business transaction. Relay delivery is at least once; consumers deduplicate event IDs. |
| `sync` | Waits for the destination acknowledgement, using the native asynchronous transport on WebFlux. It is not atomic with business changes. Delivery failure logs the sanitized event as `audit.persistence=UNCONFIRMED` and does not undo business completion. |

The audit advice runs at order **20**, outside the business transaction advice. An additional inner
advisor appends durable successes before commit. Startup verifies the actual proxy order, including
Spring's transaction advisor; changing it cannot silently remove atomic evidence.

A durable failure is recorded **after** rollback in an independent short transaction. If an outer
transaction is still active, the event waits for the outer audit, HTTP or messaging boundary to
exit. HTTP boundaries require `axiz.audit.http.enabled=true`; broker dispatch boundaries are
registered automatically. A call without one of these boundaries uses bounded `REQUIRES_NEW` as a
last resort. Imperative servlet asynchronous work must establish its own transaction/audit boundary
on the execution thread; thread-local transactions are not propagated to asynchronous tasks.

Both asynchronous queues snapshot request metadata and the parent observation at submission.
The worker restores only those values for delivery; registered transaction accessors are excluded.
Context scopes close after every event, so one request cannot leak its metadata into another.

`axiz.audit.durable.failure-timeout` (default `2s`) bounds failure persistence, including connection
acquisition. `failure-concurrency` (default `4`) limits concurrent failure writes. JDBC uses owned
virtual workers; R2DBC composes an independent `TransactionalOperator` without blocking. The
pending buffer is bounded to 1,024 events per outer boundary; overflow uses the bounded last resort.

If failure persistence cannot complete, the original business error is preserved and the sanitized
event is queued with `audit.persistence=NOT_CONFIRMED`. This is degradation, not durable evidence.
It is not counted as `lost` while the queue retains it. Metrics `axiz.audit.persistence.not.confirmed`
and `axiz.audit.persistence.unconfirmed` expose these two conditions. Existing queue health metrics
(`pending`, `lost`, `fallback`, `failures`, `worker.alive`) apply to both stacks.

Events carry two separate axes: `transactionOutcome` records confirmed local commit/rollback or
`UNKNOWN`; `operationOutcome` remains `UNKNOWN` on failure unless `RejectedBeforeEffect` explicitly
certifies no effects. A local rollback does not prove an external integration had no effect. Commit
connection failures and unclassifiable transaction failures remain `UNKNOWN`.

Outside a transaction, durable success obeys `axiz.audit.durable.outside-transaction`: `fail`
rejects publication and `async` uses the fallback. No durable guarantee is claimed for the latter.

Generated Helm charts contain optional Prometheus rules for unconfirmed/degraded/lost audit and
blocked outbox aggregates. Enable them only with the Prometheus Operator CRD installed and service
scraping configured. Adapt `monitoring.prometheusRules.metricSelector` to the actual target labels.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-audit-spring-boot-autoconfigure verify`.

## Integrity and confirmed publication

`axiz.audit.integrity.mode=HASH` seals canonical immutable events by default; `HMAC` adds
authentication using the separate secret reference `integrity.hmac-secret` and rotation ID
`integrity.hmac-key-id`. `NONE` disables sealing and is incompatible with strict publication.
An unkeyed hash chain does not prevent an actor with write access from recomputing evidence.

`axiz.audit.strict.enabled=true` publishes `StrictAuditPublisher` or its native asynchronous
counterpart. `strict.mode=DIRECT` requires an explicitly attested durable sink; `DURABLE`
requires a separately committed outbox. There is no tolerant queue/log fallback on this path.
Timeout (default 2 s) and concurrency (default 4) are bounded. A publication failure propagates;
it does not claim that a previously committed business effect was rolled back.

Memory chains emit confirmed checkpoints every `strict.checkpoint-interval` (default 1 minute)
when a strict path exists. The verifier must obtain a trusted checkpoint from the durable sink,
not a local diagnostic snapshot. `AuditChainVerifier` reports integrity separately from
completeness: without a trusted checkpoint, completeness is `NOT_ASSESSED`; a missing suffix is
detectable only up to that checkpoint. Preserve checkpoints and HMAC verification keys under
separate operational control. No local hash, metric or successful enqueue proves complete receipt.

### Kafka submission in reactive applications

Servlet publication keeps its synchronous adapter. In reactive applications the framework invokes
`KafkaTemplate.send()` on dedicated workers because metadata lookup or a full producer buffer can
wait inside `send()`. Completion means broker acknowledgement; there is no wait for that ack on a
worker or Reactor thread. The standard Kafka client is retained.

`axiz.audit.kafka.send-workers` defaults to 2 (range 1..64) and `send-queue-capacity` to 256
(range 0..65536). Typed properties validate these limits before creating the dispatcher. Admission is
bounded across queued work and unacknowledged sends; exhaustion fails immediately rather than
running on the caller. The existing Kafka timeout bounds submission plus acknowledgement and
`max.block.ms`. Metrics `axiz.transport.pending`, `.queued`, `.rejected`, and `.latency` carry the
`transport` tag (`kafka-events` or `kafka-audit`). Producers and workers close with the context.

Cancelling queued work prevents its send. Once Kafka has accepted a send, cancellation or timeout
cannot retract it: a late acknowledgement is possible, and capacity remains reserved until the
transport completes. Retrying must preserve the event ID and use consumer inbox deduplication.
