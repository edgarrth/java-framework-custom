<!-- generated -->
# axiz-core-secrets-aws

Secret resolvers for AWS Secrets Manager and Parameter Store.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-secrets-aws</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ParameterStoreResolver` | Resolves parameters (including SecureString) from AWS Systems Manager Parameter Store under a path such as `/axiz/prod/payments/`. |
| `SecretsManagerResolver` | Resolves secrets from AWS Secrets Manager. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-secrets-aws verify`.
