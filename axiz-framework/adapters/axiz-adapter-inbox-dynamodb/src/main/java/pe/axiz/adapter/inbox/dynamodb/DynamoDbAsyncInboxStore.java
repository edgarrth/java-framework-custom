package pe.axiz.adapter.inbox.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Native DynamoDB inbox. Cancellation reaches the exact put/update/delete SDK future. */
public final class DynamoDbAsyncInboxStore implements AsyncInboxStore {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final DynamoDbAsyncClient dynamo;

  private final DynamoDbInboxRequests requests;

  public DynamoDbAsyncInboxStore(DynamoDbAsyncClient dynamo, String table) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.requests = new DynamoDbInboxRequests(table);
  }

  @Override
  public CompletionStage<Acquisition> tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return Stages.recover(
        Stages.map(
            Stages.call(
                () ->
                    dynamo.putItem(
                        requests.acquire(consumer, messageId, owner, now, leaseUntil, expiresAt))),
            ignored -> Acquisition.acquired()),
        failure -> {
          if (failure instanceof ConditionalCheckFailedException conditional)
            return DynamoDbInboxRequests.current(conditional);
          throw DynamoDbInboxRequests.failure("acquire", failure);
        });
  }

  @Override
  public CompletionStage<Boolean> markProcessed(
      String consumer, String messageId, String owner, Instant at) {
    return ownership(
        Stages.call(() -> dynamo.updateItem(requests.processed(consumer, messageId, owner, at))),
        "update");
  }

  @Override
  public CompletionStage<Boolean> release(String consumer, String messageId, String owner) {
    return ownership(
        Stages.call(() -> dynamo.deleteItem(requests.release(consumer, messageId, owner))),
        "release");
  }

  /** DynamoDB's TTL service performs retention cleanup. */
  @Override
  public CompletionStage<Integer> purgeExpired(Instant now) {
    return CompletableFuture.completedFuture(0);
  }

  private static <T> CompletionStage<Boolean> ownership(CompletionStage<T> work, String operation) {
    return Stages.recover(
        Stages.map(work, ignored -> true),
        failure -> {
          if (failure instanceof ConditionalCheckFailedException) return false;
          throw DynamoDbInboxRequests.failure(operation, failure);
        });
  }
}
