<!-- generated -->
# axiz-core-sequence

Atomic formatted sequences with JDBC (PostgreSQL SEQUENCE) store.

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
| `InMemorySequenceStore` | In-memory counters for tests and local runs. |
| `JdbcSequenceStore` | Counter table on Aurora PostgreSQL: `UPDATE . |
| `ResetPolicy` | When a formatted sequence starts again from 1. |
| `SequenceExhaustedException` | The sequence exceeded the digits of its format within the period. |
| `SequenceFormat` | How a sequence value is rendered: `prefix + [period + separator] + zero-padded value`, e.g. |
| `SequenceGenerator` | Formatted, gap-tolerant sequence. |
| `SequenceStore` | Atomic counters per name and period. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-sequence verify`.
