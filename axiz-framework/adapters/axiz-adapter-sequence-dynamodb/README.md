<!-- generated -->
# axiz-adapter-sequence-dynamodb

Sequence store on a DynamoDB atomic counter.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-sequence-dynamodb</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `DynamoDbSequenceStore` | Atomic counter on DynamoDB (`ADD value :inc` with `UPDATED_NEW`). |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl adapters/axiz-adapter-sequence-dynamodb verify`.
