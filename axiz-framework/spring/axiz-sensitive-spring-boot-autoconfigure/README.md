<!-- generated -->
# axiz-sensitive-spring-boot-autoconfigure

Masking of @Sensitive and @Mask fields in JSON responses, logs and audit (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-sensitive-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AxizSensitiveAutoConfiguration` | Masking of sensitive data in the three outputs: API responses (the JSON converter or encoder of server responses), logs and audit (`ObjectMasker` shared by the logging and audit starters). |
| `AxizSensitiveProperties` | Masking policy (`axiz.sensitive.*`). |
| `SensitiveJacksonModule` | Masks properties annotated with `Sensitive` or `Mask` in JSON responses, following the `OutputChannel#RESPONSE` policy. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-sensitive-spring-boot-autoconfigure verify`.
