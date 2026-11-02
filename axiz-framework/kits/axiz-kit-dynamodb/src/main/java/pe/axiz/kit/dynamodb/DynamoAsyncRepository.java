package pe.axiz.kit.dynamodb;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageMetadata;
import pe.axiz.core.commons.Stages;
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
  private final DynamoDbAsyncClient client;
  private final String tableName;
  private final TableSchema<T> schema;
  private final DynamoDbAsyncTable<T> table;

  public DynamoAsyncRepository(
      DynamoDbAsyncClient client, String tableName, TableSchema<T> schema) {
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
        cursor);
  }

  public CompletionStage<Page<T>> queryBeginsWith(
      String partitionKey, String sortKeyPrefix, int limit, @Nullable String cursor) {
    return query(
        null,
        QueryConditional.sortBeginsWith(
            Key.builder().partitionValue(partitionKey).sortValue(sortKeyPrefix).build()),
        limit,
        cursor);
  }

  public CompletionStage<Page<T>> queryIndex(
      String indexName, String partitionKey, int limit, @Nullable String cursor) {
    return query(
        indexName,
        QueryConditional.keyEqualTo(Key.builder().partitionValue(partitionKey).build()),
        limit,
        cursor);
  }

  private CompletionStage<Page<T>> query(
      @Nullable String indexName,
      QueryConditional conditional,
      int limit,
      @Nullable String cursor) {
    return NativeDynamoCall.submit(
        client,
        enhanced -> {
          if (limit < 1 || limit > 500)
            throw new IllegalArgumentException("limit must be between 1 and 500");
          var builder = QueryEnhancedRequest.builder().queryConditional(conditional).limit(limit);
          Map<String, AttributeValue> start = CursorCodec.decode(cursor);
          if (start != null) builder.exclusiveStartKey(start);
          var currentTable = enhanced.table(tableName, schema);
          var publisher =
              indexName == null
                  ? currentTable.query(builder.build())
                  : currentTable.index(indexName).query(builder.build());
          var first = new FirstPage<T>(limit);
          publisher.subscribe(first);
          return first.result;
        });
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
      implements org.reactivestreams.Subscriber<
          software.amazon.awssdk.enhanced.dynamodb.model.Page<T>> {
    private final int limit;
    private final AtomicReference<org.reactivestreams.@Nullable Subscription> subscription =
        new AtomicReference<>();
    private final CompletableFuture<Page<T>> result = new CompletableFuture<>();

    private FirstPage(int limit) {
      this.limit = limit;
      var _ =
          result.whenComplete(
              (value, failure) -> {
                var current = subscription.get();
                if (current != null) current.cancel();
              });
    }

    @Override
    public void onSubscribe(org.reactivestreams.Subscription current) {
      if (!subscription.compareAndSet(null, current) || result.isDone()) current.cancel();
      else current.request(1);
    }

    @Override
    public void onNext(software.amazon.awssdk.enhanced.dynamodb.model.Page<T> page) {
      result.complete(
          new Page<>(
              page.items(),
              new PageMetadata(0, limit, -1, -1),
              CursorCodec.encode(page.lastEvaluatedKey())));
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
