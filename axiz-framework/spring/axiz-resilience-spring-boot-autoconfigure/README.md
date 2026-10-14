<!-- generated -->
# axiz-resilience-spring-boot-autoconfigure

Resilience4j defaults (circuit breaker, bulkhead) and Spring @Retryable configured by properties (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-resilience-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizResilienceAutoConfiguration` | Resilience4j registries with the `axiz.resilience` policies, their Micrometer metrics, and Spring's `@Retryable` / `@ConcurrencyLimit` for application methods. |
| `AxizResilienceProperties` | Resilience policies (`axiz.resilience.*`). |
| `ResiliencePolicies` | Resolves the resilience components of a named dependency from `axiz.resilience`. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-resilience-spring-boot-autoconfigure verify`.
