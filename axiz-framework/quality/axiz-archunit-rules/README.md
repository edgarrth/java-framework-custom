<!-- generated -->
# axiz-archunit-rules

Architecture rules for axiz services: general hygiene and one rule set per architectural style.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-archunit-rules</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizArchitecture` | Entry point for service tests:. |
| `BatchRules` | Batch style: readers, processors and writers in `job`; no HTTP controllers. |
| `BffRules` | BFF style: no own persistence and no domain model; it composes downstream APIs. |
| `CqrsRules` | CQRS on top of hexagonal: commands and queries are immutable records; query handlers never depend on command handlers or write ports. |
| `EdaRules` | Event-driven style: handlers live in `handler`, never expose HTTP controllers. |
| `GeneralRules` | Hygiene rules every axiz service and module follows (section 16 of the definition). |
| `HexagonalRules` | Hexagonal style: `domain` at the center, `application` (use cases and ports) around it, `adapter.in` and `adapter.out` outside, `config` wiring everything. |
| `LayeredRules` | Layered style: `controller -> service -> repository`, entities in `model`. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl quality/axiz-archunit-rules verify`.
