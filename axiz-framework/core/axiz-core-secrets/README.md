<!-- generated -->
# axiz-core-secrets

SecretResolver port with environment and file resolvers and TTL cache.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-secrets</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CachingSecretResolver` | Caches resolved secrets for a TTL to avoid calling the secret store on every use while still picking up rotations. |
| `CompositeSecretResolver` | Tries resolvers in order; the first one that has the secret wins. |
| `EnvironmentSecretResolver` | Resolves secrets from environment variables (populated by External Secrets in Kubernetes). |
| `FileSecretResolver` | Resolves secrets mounted as files (Kubernetes Secret volume): `<dir>/<name>`. |
| `SecretNotFoundException` | A required secret is not available in any source. |
| `SecretResolver` | Resolves secrets by name. |
| `SecretValue` | A secret value. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-secrets verify`.
