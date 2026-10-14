<!-- generated -->
# axiz-core-crypto-kms

AWS KMS key provider for envelope encryption with data key cache.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-crypto-kms</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `KmsDataKeyProvider` | Data keys from AWS KMS. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-crypto-kms verify`.
