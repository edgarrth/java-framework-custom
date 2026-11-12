<!-- generated -->
# axiz-core-sequence

Atomic formatted sequences with synchronous and native asynchronous ports. PostgreSQL uses a
counter table; DynamoDB uses atomic counters through the technology adapter.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-sequence</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AsyncSequenceGenerator` | JDK-only asynchronous block allocation, with cancellation and shared reservations. |
| `AsyncSequenceStore` | Native asynchronous durable counter port. |
| `InMemorySequenceStore` | In-memory counters for tests and local runs. |
| `ResetPolicy` | When a formatted sequence starts again from 1. |
| `SequenceExhaustedException` | The sequence exceeded the digits of its format within the period. |
| `SequenceFormat` | How a sequence value is rendered: `prefix + [period + separator] + zero-padded value`, e.g. |
| `SequenceGenerator` | Formatted, gap-tolerant sequence. |
| `SequenceStore` | Atomic counters per name and period. |

## Reservation guarantees

A successful block reservation must already be committed independently of the business
transaction. Rollback must not return a block still cached by another request or replica.
Gaps are expected after restart, cancellation or a failed business transaction.

The asynchronous generator shares in-flight reservations. Cancelling one waiter preserves the
others; cancelling the last waiter cancels the native future. Neither generator wraps a negative
counter after `Long.MAX_VALUE`. Formatting and reservation use the same captured period.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-sequence verify`.

## JDBC extraction in 2.0

The JDBC implementation and SQL resources now live in `axiz-adapter-sequence-jdbc`; this module contains
framework-neutral contracts and orchestration. `TransactionRunner` remains in
`pe.axiz.core.commons.tx`. Use the capability catalog/`axiz upgrade` to add JDBC adapters with
`runtime` scope in a service. See [JDBC migration](../../docs/architecture/JDBC.md).
