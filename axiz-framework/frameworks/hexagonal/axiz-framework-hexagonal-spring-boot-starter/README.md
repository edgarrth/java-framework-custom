<!-- generated -->
# axiz-framework-hexagonal-spring-boot-starter

Hexagonal style: use cases, ports and adapters, transaction per use case and domain events on commit.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-framework-hexagonal-spring-boot-starter</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ApplicationEventDomainEventSink` | In-process publication after commit through Spring application events, used when the service has no outbox (events stay inside the service). |
| `AxizHexagonalAutoConfiguration` | Hexagonal style: `@UseCase` classes run in a transaction with an observation and publish their domain events at commit, through the outbox when the service has one. |
| `AxizHexagonalProperties` | Hexagonal style settings (`axiz.hexagonal.*`). |
| `OutboxDomainEventSink` | Writes domain events to the outbox in the use case transaction, so they are published if and only if the business change commits. |
| `SpringUseCaseTransaction` | Use case transactions with Spring's transaction manager (joins an existing transaction). |
| `UseCaseInterceptor` | Wraps the public methods of `UseCase` classes: observation `axiz.usecase` with the operation and outcome, one transaction and the domain event lifecycle. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl frameworks/hexagonal/axiz-framework-hexagonal-spring-boot-starter verify`.
