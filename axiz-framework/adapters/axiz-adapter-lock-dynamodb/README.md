<!-- generated -->
# axiz-adapter-lock-dynamodb

Distributed lock on DynamoDB conditional writes.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-lock-dynamodb</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `DynamoDbDistributedLock` | Lock on DynamoDB conditional writes: acquire when the item does not exist or its lease expired; renew and release only with the owner's token. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl adapters/axiz-adapter-lock-dynamodb verify`.
