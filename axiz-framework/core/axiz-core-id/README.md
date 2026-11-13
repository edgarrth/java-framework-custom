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

UUIDv7 and ULID reserve their monotonic state with atomic compare-and-set operations. Concurrent
callers do not acquire a generator lock; clock rollback preserves ordering. UUIDv7 counter
overflow borrows the next millisecond. Timestamps outside the unsigned 48-bit range are rejected.
Random components still come from `SecureRandom`; the JDK provider controls its entropy source.
The identifiers are resource identities, not authorization credentials, as specified by
[RFC 9562](https://www.rfc-editor.org/rfc/rfc9562.html#section-8).

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-id verify`.
