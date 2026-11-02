# axiz-store-contract-tests

Contract checks every idempotency and inbox store must pass (ownership, expiry, takeover), run against fakes in unit tests and real backends in integration tests.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-store-contract-tests</artifactId>
  <scope>test</scope>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AsyncInboxStoreContract` | Reuses the owner/lease/retention contract, submitting native I/O on a supplied nonblocking executor with a bounded JUnit driver. |
| `ContractCheck` | One named property a store must satisfy. |
| `IdempotencyStoreContract` | Properties of every `IdempotencyStore`: exclusive start while the lock lives, completion and release fenced by the owner token, takeover after expiry without interference from the previous owner, and retention. |
| `InboxStoreContract` | Properties of every `InboxStore`: one delivery processes a message at a time, completion and release are fenced by the owner token, an expired lease is taken over without interference from the previous owner, and processed messages are recognized. |

## Use

A custom store passes the same checks as the framework stores:

```java
@TestFactory
Stream<DynamicTest> contract() {
  return IdempotencyStoreContract.checks(clock -> new MyIdempotencyStore(..., clock)).stream()
      .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
}
```

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl quality/axiz-store-contract-tests verify`.
