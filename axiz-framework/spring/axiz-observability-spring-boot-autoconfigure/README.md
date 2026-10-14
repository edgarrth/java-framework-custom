<!-- generated -->
# axiz-observability-spring-boot-autoconfigure

OpenTelemetry via Micrometer: framework spans, structured events, PII protection, health groups, AWS SDK tracing (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-observability-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizObservabilityAutoConfiguration` | Framework observations: architecture-point spans with business attributes, structured events, PII-safe attributes, request context on spans and no observations for probes. |
| `AxizObservabilityEnvironmentPostProcessor` | OpenTelemetry defaults (lowest precedence): OTLP export of traces and metrics to the collector given by `OTEL_EXPORTER_OTLP_ENDPOINT`, liveness and readiness probes, and resource attributes. |
| `AxizObservabilityProperties` | Observability conventions (`axiz.observability.*`). |
| `IgnoredPathsObservationPredicate` | Skips server observations for infrastructure paths such as probes and metrics scraping. |
| `RequestContextObservationFilter` | Adds request context to every observation as high-cardinality attributes: they reach spans but never become metric tags. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-observability-spring-boot-autoconfigure verify`.
