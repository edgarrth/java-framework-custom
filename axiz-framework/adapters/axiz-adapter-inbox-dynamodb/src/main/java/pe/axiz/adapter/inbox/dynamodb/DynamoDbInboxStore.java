package pe.axiz.adapter.inbox.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Objects;
import pe.axiz.core.inbox.InboxStore;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Synchronous poll-thread inbox; shares wire conditions and status decoding with the native port.
 */
public final class DynamoDbInboxStore implements InboxStore {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbClient is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
  private final DynamoDbClient dynamo;

  private final DynamoDbInboxRequests requests;

  public DynamoDbInboxStore(DynamoDbClient dynamo, String table) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.requests = new DynamoDbInboxRequests(table);
  }

  @Override
  public Acquisition tryAcquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    try {
      dynamo.putItem(requests.acquire(consumer, messageId, owner, now, leaseUntil, expiresAt));
      return Acquisition.acquired();
    } catch (ConditionalCheckFailedException failure) {
      return DynamoDbInboxRequests.current(failure);
    } catch (SdkException failure) {
      throw DynamoDbInboxRequests.failure("acquire", failure);
    }
  }

  @Override
  public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
    try {
      dynamo.updateItem(requests.processed(consumer, messageId, owner, at));
      return true;
    } catch (ConditionalCheckFailedException failure) {
      return false;
    } catch (SdkException failure) {
      throw DynamoDbInboxRequests.failure("update", failure);
    }
  }

  @Override
  public boolean release(String consumer, String messageId, String owner) {
    try {
      dynamo.deleteItem(requests.release(consumer, messageId, owner));
      return true;
    } catch (ConditionalCheckFailedException failure) {
      return false;
    } catch (SdkException failure) {
      throw DynamoDbInboxRequests.failure("release", failure);
    }
  }

  @Override
  public int purgeExpired(Instant now) {
    return 0;
  }

  static String key(String consumer, String messageId) {
    return consumer + "#" + messageId;
  }
}
