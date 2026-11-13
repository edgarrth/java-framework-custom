<!-- generated -->
# axiz-config-spring-boot-autoconfigure

ConfigMaps mounted as files and Parameter Store conventions with fail-fast validation (auto-configuration).

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

## Managed Parameter Store bootstrap

The `aws-parameterstore:` resolver selects managed credentials before the first remote read,
rejects competing `spring.cloud.aws.credentials.*` settings and preserves explicitly registered
bootstrap providers/clients. `instance-profile=false` is allowed. Resources created by bootstrap
are released on early failure or transferred to application shutdown; borrowed resources remain
owned by the extension. The integration stays optional when the AWS libraries are absent.
