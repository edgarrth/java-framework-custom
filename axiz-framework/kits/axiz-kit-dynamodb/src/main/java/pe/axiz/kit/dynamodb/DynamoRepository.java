package pe.axiz.kit.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageMetadata;
import pe.axiz.core.json.JsonWriter;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Generic repository over the DynamoDB Enhanced Client: get by partition key (and sort key),
 * conditional and plain writes, deletes, queries on the table, GSIs and LSIs with cursor
 * pagination, and transactions. Optimistic locking uses {@code @DynamoDbVersionAttribute}.
 *
 * @param <T> item type (annotated bean or immutable)
 */
public class DynamoRepository<T> {

  private final DynamoDbEnhancedClient client;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Intentional access to the underlying AWS table for operations outside the "
              + "convenience repository API.")
  private final DynamoDbTable<T> table;

  private final Supplier<CursorCodec> cursors;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public DynamoRepository(DynamoDbEnhancedClient client, String tableName, TableSchema<T> schema) {
    this(
        client,
        tableName,
        schema,
        () -> {
          throw new IllegalStateException("Authenticated cursor keys are required for pagination");
        });
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Repository extension point borrows its SDK client and table facade; construction "
              + "validates inputs and performs no remote I/O.")
  public DynamoRepository(
      DynamoDbEnhancedClient client,
      String tableName,
      TableSchema<T> schema,
      Supplier<CursorCodec> cursors) {
    this.cursors = Objects.requireNonNull(cursors);
    this.client = Objects.requireNonNull(client);
    this.table = client.table(tableName, schema);
  }

  public DynamoDbTable<T> table() {
    return table;
  }

  public Optional<T> findById(String partitionKey) {
    return Optional.ofNullable(
        table.getItem(
            r -> r.key(Key.builder().partitionValue(partitionKey).build()).consistentRead(true)));
  }

  public Optional<T> findById(String partitionKey, String sortKey) {
    return Optional.ofNullable(
        table.getItem(
            r ->
                r.key(Key.builder().partitionValue(partitionKey).sortValue(sortKey).build())
                    .consistentRead(true)));
  }

  public void save(T item) {
    table.putItem(item);
  }

  /** Inserts only if no item with the same key exists (conditional write). */
  public void insert(T item, String partitionKeyAttribute) {
    table.putItem(
        PutItemEnhancedRequest.builder(table.tableSchema().itemType().rawClass())
            .item(item)
            .conditionExpression(
                Expression.builder()
                    .expression("attribute_not_exists(#pk)")
                    .expressionNames(Map.of("#pk", partitionKeyAttribute))
                    .build())
            .build());
  }

  public T update(T item) {
    return table.updateItem(item);
  }

  public void delete(String partitionKey) {
    table.deleteItem(Key.builder().partitionValue(partitionKey).build());
  }

  public void delete(String partitionKey, String sortKey) {
    table.deleteItem(Key.builder().partitionValue(partitionKey).sortValue(sortKey).build());
  }

  /** Query by partition key with cursor pagination. */
  public Page<T> query(String partitionKey, int limit, @Nullable String cursor) {
    String binding = binding("primary", "equal", partitionKey, "");
    QueryEnhancedRequest request =
        request(
            QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionKey).build()),
            limit,
            cursor,
            binding);
    return firstPage(table.query(request), limit, binding);
  }

  /** Query by partition key and sort key prefix. */
  public Page<T> queryBeginsWith(
      String partitionKey, String sortKeyPrefix, int limit, @Nullable String cursor) {
    String binding = binding("primary", "prefix", partitionKey, sortKeyPrefix);
    QueryEnhancedRequest request =
        request(
            QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(partitionKey).sortValue(sortKeyPrefix).build()),
            limit,
            cursor,
            binding);
    return firstPage(table.query(request), limit, binding);
  }

  /** Query a global or local secondary index. */
  public Page<T> queryIndex(
      String indexName, String partitionKey, int limit, @Nullable String cursor) {
    String binding = binding(indexName, "equal", partitionKey, "");
    DynamoDbIndex<T> index = table.index(indexName);
    var pages =
        index.query(
            request(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionKey).build()),
                limit,
                cursor,
                binding));
    var first = pages.iterator().next();
    return toPage(first.items(), first.lastEvaluatedKey(), limit, binding);
  }

  /** Runs a transaction with the given builder customization. */
  public void transact(Consumer<TransactWriteItemsEnhancedRequest.Builder> builder) {
    TransactWriteItemsEnhancedRequest.Builder b = TransactWriteItemsEnhancedRequest.builder();
    builder.accept(b);
    client.transactWriteItems(b.build());
  }

  private String binding(String index, String operation, String partition, String prefix) {
    return JsonWriter.write(List.of(table.tableName(), index, operation, partition, prefix));
  }

  private QueryEnhancedRequest request(
      QueryConditional conditional, int limit, @Nullable String cursor, String binding) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("limit must be between 1 and 500");
    }
    QueryEnhancedRequest.Builder b =
        QueryEnhancedRequest.builder().queryConditional(conditional).limit(limit);
    Map<String, AttributeValue> start = cursors.get().decode(cursor, binding);
    if (start != null) {
      b.exclusiveStartKey(start);
    }
    return b.build();
  }

  private Page<T> firstPage(PageIterable<T> pages, int limit, String binding) {
    var first = pages.iterator().next();
    return toPage(first.items(), first.lastEvaluatedKey(), limit, binding);
  }

  private Page<T> toPage(
      List<T> items, @Nullable Map<String, AttributeValue> last, int limit, String binding) {
    return new Page<>(
        items, new PageMetadata(0, limit, -1, -1), cursors.get().encode(last, binding));
  }
}
