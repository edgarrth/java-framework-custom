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
| `AxizEdaProperties` | EDA style settings (`axiz.eda.*`). |
| `Reactions` | Emits events in reaction to a consumed one (choreography) through the outbox, inside the handler transaction. |
| `ReactiveEventHandlerPostProcessor` | Wraps every `ReactiveTypedEventHandler` bean in `ReactiveTransactionalEventHandler`: a reactive transaction when a reactive transaction manager exists (R2DBC), the request context and the handler timeout in every case. |
| `ReactiveReactions` | Reactive `Reactions`: emits events in reaction to a consumed one through the R2DBC outbox, inside the reactive transaction of the handler. |
| `ReactiveTransactionalEventHandler` | Runs a reactive handler in one reactive transaction (its R2DBC writes and the events it enqueues in the outbox commit together), with the request metadata and the trace of the consumed message in the Reactor context, and waits for it on the consumer thread. |
| `TransactionalEventHandler` | Runs a handler in one transaction, so its writes and the events it enqueues in the outbox commit together or not at all. |
| `TransactionalEventHandlerPostProcessor` | Wraps every blocking `EventHandler` bean in a transaction when a transaction manager exists; reactive handlers are wrapped by `ReactiveEventHandlerPostProcessor`. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl structures/eda/axiz-structure-eda-spring-boot-starter verify`.
