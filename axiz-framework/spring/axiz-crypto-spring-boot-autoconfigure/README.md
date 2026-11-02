<!-- generated -->
# axiz-crypto-spring-boot-autoconfigure

Field encryption with key rotation, KMS envelope encryption, blind index, password hashing and JOSE (auto-configuration).

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-crypto-spring-boot-autoconfigure</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ReactiveEnvelopeEncryption` | Deferred native KMS envelope encryption for reactive applications; no synchronous client fallback. |
| `AxizCryptoAutoConfiguration` | Field encryption with keys from the secret store, blind indexes, and password hashing (Argon2id by default). |
| `AxizCryptoKmsAutoConfiguration` | Envelope encryption with AWS KMS data keys (cached by age and use count). |
| `AxizCryptoProperties` | Cryptography (`axiz.crypto.*`). |
| `EncryptionRegistrar` | Publishes the field encryptor to `EncryptionRegistry` for converters that JPA or DynamoDB instantiate outside the container. |
| `SpringPasswordHasher` | Argon2id and BCrypt through Spring Security's encoders. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl spring/axiz-crypto-spring-boot-autoconfigure verify`.
