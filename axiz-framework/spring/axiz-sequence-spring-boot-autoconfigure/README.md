<!-- generated -->
# axiz-sequence-spring-boot-autoconfigure

Formatted atomic sequences on JDBC, native R2DBC or DynamoDB, selected by application stack.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-sequence-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizSequenceAutoConfiguration` | Atomic business sequences on Aurora (counter table) or DynamoDB (atomic counter). |
| `AxizSequenceProperties` | Business sequences (`axiz.sequence.*`), e.g. |
| `Sequences` | Generators for the configured sequences; unknown names fail fast. |
| `ReactiveSequences` | Deferred `Mono<String>` facade over native asynchronous allocation. |

Related types: `AxizSequenceDynamoDbAutoConfiguration` live in `axiz-sequence-dynamodb-spring-boot-autoconfigure`; `AxizSequenceR2dbcAutoConfiguration` live in `axiz-sequence-r2dbc-spring-boot-autoconfigure`.

## Stack and transaction boundaries

Servlet and non-web applications publish `SequenceStore` and `Sequences`. Reactive applications
publish `ReactiveSequences`, with `DynamoDbAsyncClient`/`AsyncSequenceStore` or the kit's
`ReactiveSequenceStore`. They do not create synchronous clients or JDBC sequence beans.
The default relational setting `jdbc` selects native R2DBC in a reactive application;
`axiz.sequence.store=r2dbc` is also accepted there. Missing native backends fail at startup.

Reservations commit independently of business rollback. Hikari and R2DBC use a separate,
bounded reservation pool, so a fully occupied business pool cannot prevent counter progress.
The R2DBC port remains `Mono`; the private Spring bridge to the JDK block allocator intentionally
does not carry a business transaction into the independent reservation.

```yaml
axiz:
  sequence:
    pool:
      max-size: 2
      acquire-timeout: 5s
    sequences:
      operation:
        prefix: OP-
        width: 8
        block-size: 10
```

`sequences.next("operation")` returns `String` for `Sequences` and `Mono<String>` for
`ReactiveSequences`. Each reactive subscription allocates a value. Blocks may leave gaps.
Relational tables are provisioned through the service's migration; generated services include
the sequence table when selecting this capability.

For custom pooled JDBC implementations, provide a dedicated `axizSequenceDataSource`; for custom
R2DBC factories, provide `axizSequenceConnectionFactory`. Mark the main resource `@Primary` when
declaring several beans. The framework closes only reservation resources it creates; supplied
resources retain their own lifecycle. Database grants and routing must allow counter writes.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-sequence-spring-boot-autoconfigure verify`.
