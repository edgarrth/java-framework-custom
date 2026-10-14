<!-- generated -->
# axiz-logging-spring-boot-autoconfigure

Structured JSON logging, operation logging with masking and HTTP request logs (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-logging-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizLoggingAutoConfiguration` | `LogOperation` through an infrastructure advisor (AOT and native friendly, no AspectJ) and the HTTP access log for servlet and reactive applications. |
| `AxizLoggingEnvironmentPostProcessor` | Structured logging defaults, applied before the logging system starts. |
| `AxizLoggingProperties` | Logging conventions (`axiz.logging.*`). |
| `FailureKind` | Tells expected business rejections (logged at WARN) from failures (logged at ERROR). |
| `HttpLoggingFilter` | One access-log line per request (logger `axiz.http`) with method, path, status, duration and allow-listed headers. |
| `HttpLoggingWebFilter` | Reactive access log: same line as the servlet filter, without bodies. |
| `LogOperationInterceptor` | Applies `LogOperation`: entry and exit lines with duration and outcome. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-logging-spring-boot-autoconfigure verify`.
