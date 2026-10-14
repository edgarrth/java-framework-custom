<!-- generated -->
# axiz-core-persistence-dynamodb

Generic DynamoDB repository over the Enhanced Client with cursor pagination and converters.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-persistence-dynamodb</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CursorCodec` | Opaque pagination cursor: the DynamoDB `LastEvaluatedKey` (string and number keys) encoded as base64url JSON. |
| `DynamoRepository` | Generic repository over the DynamoDB Enhanced Client: get by partition key (and sort key), conditional and plain writes, deletes, queries on the table, GSIs and LSIs with cursor pagination, and transactions. |
| `EncryptedAttributeConverter` | Encrypts String attributes with the registered field encryptor: `@DynamoDbConvertedBy(EncryptedAttributeConverter.class)`. |
| `MoneyAttributeConverter` | Stores `Money` as its text form (`"PEN 150.00"`), never as a binary float. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl core/axiz-core-persistence-dynamodb verify`.
