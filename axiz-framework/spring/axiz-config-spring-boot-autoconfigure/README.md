<!-- generated -->
# axiz-config-spring-boot-autoconfigure

ConfigMaps through Spring Cloud Kubernetes and Parameter Store conventions with fail-fast validation (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-config-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizConfigAutoConfiguration` | Startup validation of the externalized configuration. |
| `AxizConfigEnvironmentPostProcessor` | Applies the configuration conventions before config data is loaded. |
| `AxizConfigProperties` | Configuration conventions (`axiz.config.*`). |
| `RequiredPropertiesVerifier` | Fails startup when a property declared in `axiz.config.required` is missing or blank. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-config-spring-boot-autoconfigure verify`.
