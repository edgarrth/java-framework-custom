<!-- generated -->
# axiz-core-logging

Structured logging fields, scoped MDC, operation logging and header allow-list.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-logging</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `HttpLogFormatter` | Formats HTTP request/response logs: only allow-listed headers are written (Authorization and Cookie never), JSON bodies are masked by key and truncated. |
| `LogFields` | Standard structured-log field names (MDC keys). |
| `LogOperation` | Logs entry and exit of a method (or of every public method of a class) with duration, outcome and masked arguments and result. |
| `NoLog` | Excludes a parameter, a method result or a whole method from operation and HTTP logs. |
| `OperationLogger` | Writes the entry and exit lines of an operation. |
| `ScopedMdc` | Puts values in the MDC for a scope and restores the previous values on close:. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-logging verify`.
