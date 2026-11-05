package pe.axiz.adapter.idempotency.dynamodb;

import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.HELD_BY_OWNER;
import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.item;
import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.n;
import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.names;
import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.s;
import static pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore.values;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.idempotency.AsyncIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** Native asynchronous DynamoDB idempotency, with the same durable records and fencing as 1.x. */
public final class DynamoDbAsyncIdempotencyStore implements AsyncIdempotencyStore {
  private final DynamoDbAsyncClient dynamo;
  private final String table;
  private final Clock clock;

  public DynamoDbAsyncIdempotencyStore(DynamoDbAsyncClient dynamo, String table, Clock clock) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
    this.clock = Objects.requireNonNull(clock);
  }

  private static CompletionStage<Boolean> conditional(CompletionStage<?> write) {
    return Stages.recover(
        Stages.map(write, ignored -> true),
        failure -> {
          if (failure instanceof ConditionalCheckFailedException) return false;
          throw new CompletionException(failure);
        });
  }

  @Override
  public CompletionStage<Boolean> compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    if (replacement != null && !expected.key().equals(replacement.key()))
      throw new IllegalArgumentException("different record key");
    if (replacement == null)
      return conditional(
          dynamo.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(table)
                  .key(Map.of("pk", s(expected.key())))
                  .conditionExpression(
                      "attribute_exists(pk) AND #data = :expected AND expiresAtMs > :now")
                  .expressionAttributeNames(Map.of("#data", "data"))
                  .expressionAttributeValues(
                      Map.of(
                          ":expected",
                          s(StoredResponseCodec.write(expected)),
                          ":now",
                          n(clock.millis())))
                  .build()));
    return conditional(
        dynamo.putItem(
            PutItemRequest.builder()
                .tableName(table)
                .item(item(replacement))
                .conditionExpression(
                    "attribute_exists(pk) AND #data = :expected AND expiresAtMs > :now")
                .expressionAttributeNames(Map.of("#data", "data"))
                .expressionAttributeValues(
                    Map.of(
                        ":expected",
                        s(StoredResponseCodec.write(expected)),
                        ":now",
                        n(clock.millis())))
                .build()));
  }

  @Override
  public CompletionStage<Boolean> tryStart(IdempotencyRecord record) {
    return conditional(
        dynamo.putItem(
            PutItemRequest.builder()
                .tableName(table)
                .item(item(record))
                .conditionExpression("attribute_not_exists(pk) OR expiresAtMs < :now")
                .expressionAttributeValues(Map.of(":now", n(clock.millis())))
                .build()));
  }

  @Override
  public CompletionStage<Optional<IdempotencyRecord>> find(String key, Instant now) {
    return Stages.map(
        dynamo.getItem(
            GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", s(key)))
                .consistentRead(true)
                .build()),
        answer -> {
          var item = answer.item();
          if (item == null || item.isEmpty() || !item.containsKey("data")) return Optional.empty();
          IdempotencyRecord record = StoredResponseCodec.read(key, item.get("data").s());
          return !record.liveAt(now) ? Optional.empty() : Optional.of(record);
        });
  }

  @Override
  public CompletionStage<Boolean> complete(
      String key, String owner, StoredResponse response, Instant expiresAt) {
    return completeAttempt(key, owner, response, expiresAt, 0);
  }

  private CompletionStage<Boolean> completeAttempt(
      String key, String owner, StoredResponse response, Instant expiresAt, int attempt) {
    if (attempt >= 16) return CompletableFuture.completedFuture(false);
    return Stages.compose(
        find(key, clock.instant()),
        current -> {
          if (current.isEmpty() || !current.get().heldBy(owner))
            return CompletableFuture.completedFuture(false);
          return Stages.compose(
              compareAndSet(current.get(), current.get().completed(response, expiresAt)),
              changed ->
                  changed
                      ? CompletableFuture.completedFuture(true)
                      : completeAttempt(key, owner, response, expiresAt, attempt + 1));
        });
  }

  @Override
  public CompletionStage<Boolean> release(String key, String owner) {
    return conditional(
        dynamo.deleteItem(
            DeleteItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", s(key)))
                .conditionExpression(HELD_BY_OWNER)
                .expressionAttributeNames(names())
                .expressionAttributeValues(values(owner))
                .build()));
  }
}
