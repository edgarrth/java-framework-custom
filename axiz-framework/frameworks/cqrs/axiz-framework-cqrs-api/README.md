<!-- generated -->
# axiz-framework-cqrs-api

CQRS on top of hexagonal: commands, queries, buses with an ordered pipeline and projections (contracts, plain Java).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-framework-cqrs-api</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AccessPolicies` | The access policies of an application: runs the ones matching a request and reports request types that nothing protects. |
| `AccessPolicy` | Object-level authorization of a command or query type: decides whether the caller may act on the resources the request names (customer, account, order), beyond the authorities of `RequiresAuthority`. |
| `AuditedCommand` | Command recorded in the audit trail with its outcome. |
| `Buses` | Plain-Java command and query buses. |
| `Caller` | Who sends a command or query, as seen by an `AccessPolicy`. |
| `CommandBus` | Sends commands through the pipeline to their handler. |
| `CommandHandler` | Handles one command type. |
| `HandlerNotFoundException` | No handler is registered for a request type: a configuration error, not a client error. |
| `IdempotentCommand` | Command protected by an idempotency key: a repeated key returns the first result. |
| `Invocation` | One request travelling through the pipeline. |
| `OpenAccess` | Explicit, reviewed decision that this request needs no authority and no `AccessPolicy`: for example a command only sent by an event consumer and never exposed over HTTP, or a public catalog query. |
| `PipelineBehavior` | Cross-cutting step around handlers. |
| `Projection` | Keeps a read model up to date from events (domain events in process, or integration events through the EDA style). |
| `QueryBus` | Sends queries through the pipeline to their handler. |
| `QueryHandler` | Handles one query type; never changes state. |
| `ReactiveCommandHandler` | Handles one command type without blocking (reactive services on WebFlux and R2DBC). |
| `ReactiveQueryHandler` | Handles one query type without blocking; never changes state. |
| `Request` | A message handled by exactly one handler. |
| `RequiresAuthority` | Authorities (any of them) the caller needs to send a command or query. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl frameworks/cqrs/axiz-framework-cqrs-api verify`.
