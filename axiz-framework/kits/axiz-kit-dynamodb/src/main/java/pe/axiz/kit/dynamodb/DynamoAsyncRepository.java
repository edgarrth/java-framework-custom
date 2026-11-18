package pe.axiz.kit.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageMetadata;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.json.JsonWriter;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Native asynchronous repository. Cancellation reaches the low-level SDK request, including a
 * pending query page. Queries fetch exactly one page; converters must perform CPU-only work. The
 * application owns the supplied client. SDK default optimistic locking remains enabled.
 */
public final class DynamoAsyncRepository<T> {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final DynamoDbAsyncClient client;

  private final String tableName;
  private final TableSchema<T> schema;
  private final DynamoDbAsyncTable<T> table;
  private final Supplier<CursorCodec> cursors;

  public DynamoAsyncRepository(
      DynamoDbAsyncClient client, String tableName, TableSchema<T> schema) {
    this(
        client,
        tableName,
        schema,
        () -> {
          throw new IllegalStateException("Authenticated cursor keys are required for pagination");
        });
  }

  public DynamoAsyncRepository(
      DynamoDbAsyncClient client,
      String tableName,
      TableSchema<T> schema,
      Supplier<CursorCodec> cursors) {
    this.cursors = Objects.requireNonNull(cursors);
    this.client = Objects.requireNonNull(client);
    this.tableName = Objects.requireNonNull(tableName);
    this.schema = Objects.requireNonNull(schema);
    this.table =
        DynamoDbEnhancedAsyncClient.builder()
            .dynamoDbClient(client)
            .build()
            .table(tableName, schema);
  }

  /**
   * SDK table for transaction descriptions or direct SDK use outside this repository's contract.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Intentional SDK table facade for operations outside the convenience repository "
              + "API; the application owns the borrowed transport.")
  public DynamoDbAsyncTable<T> table() {
    return table;
  }

  public CompletionStage<Optional<T>> findById(String partitionKey) {
    return find(Key.builder().partitionValue(partitionKey).build());
  }

  public CompletionStage<Optional<T>> findById(String partitionKey, String sortKey) {
    return find(Key.builder().partitionValue(partitionKey).sortValue(sortKey).build());
  }

  private CompletionStage<Optional<T>> find(Key key) {
    return NativeDynamoCall.submit(
        client,
        enhanced ->
            enhanced
                .table(tableName, schema)
                .getItem(r -> r.key(key).consistentRead(true))
                .thenApply(Optional::ofNullable));
  }

  public CompletionStage<Void> save(T item) {
    return NativeDynamoCall.submit(
        client, enhanced -> enhanced.table(tableName, schema).putItem(item));
  }

  /** Inserts only when the partition-key attribute does not exist. */
  public CompletionStage<Void> insert(T item, String partitionKeyAttribute) {
    return NativeDynamoCall.submit(
        client,
        enhanced ->
            enhanced
                .table(tableName, schema)
                .putItem(
                    PutItemEnhancedRequest.builder(schema.itemType().rawClass())
                        .item(item)
                        .conditionExpression(
                            Expression.builder()
                                .expression("attribute_not_exists(#pk)")
                                .expressionNames(Map.of("#pk", partitionKeyAttribute))
                                .build())
                        .build()));
  }

  public CompletionStage<T> update(T item) {
    return NativeDynamoCall.submit(
        client, enhanced -> enhanced.table(tableName, schema).updateItem(item));
  }

  public CompletionStage<Void> delete(String partitionKey) {
    return delete(Key.builder().partitionValue(partitionKey).build());
  }

  public CompletionStage<Void> delete(String partitionKey, String sortKey) {
    return delete(Key.builder().partitionValue(partitionKey).sortValue(sortKey).build());
  }

  private CompletionStage<Void> delete(Key key) {
    return Stages.ignore(
        NativeDynamoCall.submit(
            client, enhanced -> enhanced.table(tableName, schema).deleteItem(key)));
  }

  public CompletionStage<Page<T>> query(String partitionKey, int limit, @Nullable String cursor) {
    return query(
        null,
        QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionKey).build()),
        limit,
        cursor,
        binding("primary", "equal", partitionKey, ""));
  }

  public CompletionStage<Page<T>> queryBeginsWith(
      String partitionKey, String sortKeyPrefix, int limit, @Nullable String cursor) {
    return query(
        null,
        QueryConditional.sortBeginsWith(
            Key.builder().partitionValue(partitionKey).sortValue(sortKeyPrefix).build()),
        limit,
        cursor,
        binding("primary", "prefix", partitionKey, sortKeyPrefix));
  }

  public CompletionStage<Page<T>> queryIndex(
      String indexName, String partitionKey, int limit, @Nullable String cursor) {
    return query(
        indexName,
        QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionKey).build()),
        limit,
        cursor,
        binding(indexName, "equal", partitionKey, ""));
  }

  private CompletionStage<Page<T>> query(
      @Nullable String indexName,
      QueryConditional conditional,
      int limit,
      @Nullable String cursor,
      String binding) {
    return NativeDynamoCall.submit(
        client,
        enhanced -> {
          if (limit < 1 || limit > 500)
            throw new IllegalArgumentException("limit must be between 1 and 500");
          var builder = QueryEnhancedRequest.builder().queryConditional(conditional).limit(limit);
          Map<String, AttributeValue> start = cursors.get().decode(cursor, binding);
          if (start != null) builder.exclusiveStartKey(start);
          var currentTable = enhanced.table(tableName, schema);
          var publisher =
              indexName == null
                  ? currentTable.query(builder.build())
                  : currentTable.index(indexName).query(builder.build());
          var first = new FirstPage<T>(limit, cursors.get(), binding);
          publisher.subscribe(first);
          return first.result;
        });
  }

  private String binding(String index, String operation, String partition, String prefix) {
    return JsonWriter.write(List.of(tableName, index, operation, partition, prefix));
  }

  public CompletionStage<Void> transact(
      Consumer<TransactWriteItemsEnhancedRequest.Builder> builder) {
    return NativeDynamoCall.submit(
        client,
        enhanced -> {
          var request = TransactWriteItemsEnhancedRequest.builder();
          builder.accept(request);
          return enhanced.transactWriteItems(request.build());
        });
  }

  /** SDK interoperability is private; no Reactive Streams types in the repository's API. */
  private static final class FirstPage<T>
      implements Subscriber<software.amazon.awssdk.enhanced.dynamodb.model.Page<T>> {
    private final int limit;
    private final CursorCodec cursors;
    private final String binding;
    private final AtomicReference<@Nullable Subscription> subscription = new AtomicReference<>();
    private final CompletableFuture<Page<T>> result = new CompletableFuture<>();

    private FirstPage(int limit, CursorCodec cursors, String binding) {
      this.limit = limit;
      this.cursors = cursors;
      this.binding = binding;
      Stages.observe(
          result,
          (value, failure) -> {
            var current = subscription.get();
            if (current != null) current.cancel();
          },
          result::completeExceptionally);
    }

    @Override
    public void onSubscribe(Subscription current) {
      if (!subscription.compareAndSet(null, current) || result.isDone()) current.cancel();
      else current.request(1);
    }

    @Override
    public void onNext(software.amazon.awssdk.enhanced.dynamodb.model.Page<T> page) {
      try {
        result.complete(
            new Page<>(
                page.items(),
                new PageMetadata(0, limit, -1, -1),
                cursors.encode(page.lastEvaluatedKey(), binding)));
      } catch (RuntimeException failure) {
        result.completeExceptionally(failure);
      }
    }

    @Override
    public void onError(Throwable failure) {
      result.completeExceptionally(failure);
    }

    @Override
    public void onComplete() {
      result.complete(new Page<>(List.of(), new PageMetadata(0, limit, -1, -1), null));
    }
  }
}
