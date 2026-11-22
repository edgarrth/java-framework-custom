<!-- generated -->
# axiz-structure-eda-spring-boot-starter

Event-driven style: typed event handlers, idempotent consumption, outbox publication, retries and DLQ.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-structure-eda-spring-boot-starter</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizEdaAutoConfiguration` | Event-driven style: transactional handlers (business writes, inbox and outbox commit together) and choreography through `Reactions`, or for reactive services non-blocking handlers in a reactive transaction with `ReactiveReactions`. |
| `Reactions` | Emits events in reaction to a consumed one (choreography) through the outbox, inside the handler transaction. |
| `ReactiveReactions` | Reactive `Reactions`: emits events in reaction to a consumed one through the R2DBC outbox, inside the reactive transaction of the handler. |

Related types: `ReactiveEventHandlerPostProcessor`, `ReactiveTransactionalEventHandler`, `TransactionalEventHandler`, `TransactionalEventHandlerPostProcessor` live in `axiz-messaging-spring-boot-autoconfigure`.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl structures/eda/axiz-structure-eda-spring-boot-starter verify`.

The starter reuses `axiz.messaging.*` settings and core messaging contracts. EDA has no separate API artifact.
