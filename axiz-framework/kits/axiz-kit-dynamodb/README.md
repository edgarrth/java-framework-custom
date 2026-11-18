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
| `CursorCodec` | Authenticated, query-bound pagination cursor with keyed rotation and optional AES-GCM encryption (string, number and binary keys). |
| `DynamoRepository` | Generic repository over the DynamoDB Enhanced Client: get by partition key (and sort key), conditional and plain writes, deletes, queries on the table, GSIs and LSIs with cursor pagination, and transactions. |
| `DynamoAsyncRepository` | Native CompletionStage API with SDK optimistic locking and cancellation of the exact low-level request; queries fetch one bounded page. |
| `EncryptedAttributeConverter` | Injected per-column String converter with an explicit encryption context. |
| `EncryptedTableSchema` | Builds an Enhanced Client schema with injected converters and unique column contexts. |
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

## Injected field encryption

Build the schema in the persistence adapter at startup and supply the `FieldEncryptor` owned
by that application context. `EncryptedAttributeConverter` has no zero-argument constructor:
do not register it through `@DynamoDbConvertedBy` or a global registry.

```java
TableSchema<Customer> schema =
    new EncryptedTableSchema<>(Customer.class, Customer::new, fieldEncryptor)
        .attribute(StaticAttribute.builder(Customer.class, String.class)
            .name("id").getter(Customer::getId).setter(Customer::setId)
            .tags(StaticAttributeTags.primaryPartitionKey()).build())
        .encryptedString("dni", "customer.dni", Customer::getDni, Customer::setDni)
        .build();
```

The model supplies the constructor, getters and setters referenced above. Pass the resulting
schema to `DynamoRepositories.create(table, schema)` or its reactive counterpart; the class-only
overload uses SDK annotation discovery and cannot inject this converter. A converter provider
selected only by Java type would encrypt every String with one context, so encrypted attributes
are registered individually. Names and encrypted-column contexts cannot be duplicated.

Mapping uses local keys and performs no KMS request. The helper prevents ciphertext substitution
between columns. Attribute converters cannot bind a ciphertext to another attribute's row ID;
use `FieldProtector` with explicit row binding before constructing the persistence item when
that protection is required. Store its encoded ciphertext without applying another converter.

`EncryptedTableSchemaTest` exercises the SDK's `itemToMap`/`mapToItem`, cross-column substitution,
independent key providers and malformed attribute types; it does not require a DynamoDB server.

## Authenticated pagination cursors

Use `DynamoRepositories.cursors()` (or the reactive repository factory) with the `dynamodb-cursor`
capability. Configure `axiz.dynamodb.cursor.current-key-id`, `key-ids`, `secret-prefix` (default
`cursor-key-`) and `encrypt` (default `false`). Key material is resolved from secrets once at
startup. Provision the same keys on all replicas; deploy a new key to every replica before making
it current, then retire the old key only after its cursor lifetime policy allows it.

The format is `v1.<kid>.<payload>.<mac>`. HMAC authenticates the version, key ID, payload and
caller's binding. The repository binds table/index, operation, partition and sort prefix; custom
queries must bind all their filters and authorization scope. A signature does not authorize a
query: derive tenant/customer constraints from the authenticated caller and validate returned keys.
Set `encrypt=true` when key attributes in the cursor must also remain confidential.

For custom queries, use `codec.encode(lastEvaluatedKey, binding)` and
`codec.decode(cursor, binding)`. Repositories constructed without a codec can perform CRUD, but
paginated queries fail with an actionable configuration error. There is no unsigned compatibility
mode: malformed, altered, cross-query, unsigned or retired-key cursors raise `INVALID_CURSOR`
(BAD_REQUEST/400). Clients restart pagination rather than trying another tenant's cursor.
