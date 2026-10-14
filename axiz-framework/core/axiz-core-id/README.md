<!-- generated -->
# axiz-core-id

Time-ordered UUIDv7, ULID, correlation and idempotency ids.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-id</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CorrelationIds` | Correlation and request ids: generation and validation of inbound values. |
| `IdGenerator` | Generates unique identifiers as strings. |
| `IdempotencyKeys` | Idempotency keys: validation of client keys and deterministic derivation from parts. |
| `Ulid` | Universally Unique Lexicographically Sortable Identifier: 48-bit timestamp plus 80 random bits, encoded in 26 Crockford base32 characters. |
| `UuidV7` | Time-ordered UUID version 7 (RFC 9562). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-id verify`.
