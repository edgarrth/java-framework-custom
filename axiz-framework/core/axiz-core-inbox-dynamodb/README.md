<!-- generated -->
# axiz-core-inbox-dynamodb

Inbox store on DynamoDB with TTL.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-inbox-dynamodb</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `DynamoDbInboxStore` | Inbox on DynamoDB. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-inbox-dynamodb verify`.
