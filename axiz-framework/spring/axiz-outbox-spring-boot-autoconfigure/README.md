<!-- generated -->
# axiz-outbox-spring-boot-autoconfigure

Relay startup validates the actual `EventPublisher` budget, including routing and observation
wrappers, against the lease. Set `axiz.outbox.relay.publish-budget` for unknown/custom publishers;
it cannot reduce a known route's bound. `axiz.outbox.relay.claim-allowance` defaults to 2 s and is
only startup sizing, not a hard limit on claim latency. Outbox writers with no local relay do not
require a publisher budget; schema checks remain active.

Optional relay metrics are `axiz.outbox.relay.claim.duration` and
`axiz.outbox.relay.budget.exhausted`. A slow claim alone does not increment the exhaustion counter.

Transactional outbox on Aurora PostgreSQL (JDBC or R2DBC) with background relay (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-outbox-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizOutboxAutoConfiguration` | Transactional outbox: events written in the business transaction and relayed at-least-once to MSK, SNS or SQS through the messaging starter's publisher. |
| `AxizOutboxProperties` | Transactional outbox (`axiz.outbox.*`) on Aurora PostgreSQL, over JDBC or R2DBC. |
| `AxizOutboxR2dbcAutoConfiguration` | Transactional outbox for reactive services on Aurora over R2DBC. |
| `CachedPendingCount` | Pending-record count for the `axiz.outbox.pending` gauge when the store blocks on a reactive driver: a scrape never runs the query on its own thread (which may be an event loop); it reads the last value and, when it is older than `maxAge`, starts one refresh on a virtual thread. |
| `OutboxRelayRunner` | Background relay on a virtual thread: polls, publishes in batches while there is work, sleeps otherwise, and purges published records periodically. |
| `ReactiveTransactionalEvents` | Reactive counterpart of `TransactionalEvents` for R2DBC services: the event is inserted in the outbox with the same connection and transaction as the business change (`TransactionalOperator` or `@Transactional` on a method returning `Mono`/`Flux`); the relay publishes it after commit. |
| `TransactionalEvents` | Records events in the outbox inside the caller's transaction; the relay publishes them after commit. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-outbox-spring-boot-autoconfigure verify`.

## Technical payload protection

`axiz.outbox.payload-protection=envelope` opts into authenticated payload encryption;
`none` is the default. Envelope mode requires the matching synchronous/native
`EnvelopeEncryptor` and fails startup when it is absent. Protected records cannot
be read in `none` mode; migration and retained read keys must be planned before
changing the mode. See [technical storage protection](../../../axiz-docs/docs/guias/proteccion-de-almacenes-tecnicos.md).
