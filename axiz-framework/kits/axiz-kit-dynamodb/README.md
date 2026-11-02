<!-- generated -->
# axiz-kit-dynamodb

Generic DynamoDB repository over the Enhanced Client with cursor pagination and converters.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-kit-dynamodb</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `CursorCodec` | Opaque pagination cursor: the DynamoDB `LastEvaluatedKey` (string and number keys) encoded as base64url JSON. |
| `DynamoRepository` | Generic repository over the DynamoDB Enhanced Client: get by partition key (and sort key), conditional and plain writes, deletes, queries on the table, GSIs and LSIs with cursor pagination, and transactions. |
| `DynamoAsyncRepository` | Native CompletionStage API with SDK optimistic locking and cancellation of the exact low-level request; queries fetch one bounded page. |
| `EncryptedAttributeConverter` | Encrypts String attributes with the registered field encryptor: `@DynamoDbConvertedBy(EncryptedAttributeConverter.class)`. |
| `MoneyAttributeConverter` | Stores `Money` as its text form (`"PEN 150.00"`), never as a binary float. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl kits/axiz-kit-dynamodb verify`.

## Asynchronous use

Supply an application-owned `DynamoDbAsyncClient` and construct the schema at startup. The
repository does not close that client. Mapping and attribute converters run on the caller or SDK
completion thread and must perform CPU-only work; a converter that calls synchronous KMS is not
appropriate for this API. Queries preserve the same opaque cursor format as the synchronous
repository. The returned `table()` is an SDK escape hatch for transaction descriptions; direct
SDK operations have the SDK's own cancellation semantics.
