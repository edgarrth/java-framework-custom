<!-- generated -->
# axiz-core-featureflags

Feature flags with targeting by channel, tenant or percentage; OpenFeature-compatible provider.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-featureflags</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `EvaluationContext` | Who a flag is evaluated for. |
| `FeatureFlags` | Evaluates feature flags. |
| `FlagDefinition` | A feature flag. |
| `FlagProvider` | Source of flag definitions (ConfigMap-bound properties, Parameter Store). |
| `MapFlagProvider` | Flags from configuration (bound from the service ConfigMap). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-featureflags verify`.
