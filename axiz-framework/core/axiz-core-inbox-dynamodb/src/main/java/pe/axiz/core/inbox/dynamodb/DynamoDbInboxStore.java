package pe.axiz.core.inbox.dynamodb;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Inbox on DynamoDB. Item key {@code pk = consumer#messageId}; acquisition is a conditional put
 * (new item, or expired in-progress lease) that records the owner; completion and release are
 * conditioned on that owner. Retention uses the table TTL attribute {@code ttl}.
 */
public final class DynamoDbInboxStore implements InboxStore {

  private final DynamoDbClient dynamo;
  private final String table;

  public DynamoDbInboxStore(DynamoDbClient dynamo, String table) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
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
      dynamo.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(
                  Map.of(
                      "pk", s(key(consumer, messageId)),
                      "status", s("IN_PROGRESS"),
                      "owner", s(owner),
                      "leaseUntil", n(leaseUntil.toEpochMilli()),
                      "ttl", n(expiresAt.getEpochSecond())))
              .conditionExpression(
                  "attribute_not_exists(pk) OR (#st = :inProgress AND leaseUntil < :now)")
              .expressionAttributeNames(Map.of("#st", "status"))
              .expressionAttributeValues(
                  Map.of(":inProgress", s("IN_PROGRESS"), ":now", n(now.toEpochMilli())))
              .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
              .build());
      return Acquisition.acquired();
    } catch (ConditionalCheckFailedException e) {
      Map<String, AttributeValue> current = e.hasItem() ? e.item() : Map.of();
      AttributeValue status = current.get("status");
      if (status != null && "PROCESSED".equals(status.s())) {
        return Acquisition.processed();
      }
      AttributeValue until = current.get("leaseUntil");
      return Acquisition.busy(
          until == null ? null : Instant.ofEpochMilli(Long.parseLong(until.n())));
    } catch (SdkException e) {
      throw new InboxException("DynamoDB inbox acquire failed", e);
    }
  }

  @Override
  public boolean markProcessed(String consumer, String messageId, String owner, Instant at) {
    try {
      dynamo.updateItem(
          UpdateItemRequest.builder()
              .tableName(table)
              .key(Map.of("pk", s(key(consumer, messageId))))
              .updateExpression("SET #st = :processed, processedAt = :at")
              .conditionExpression("#owner = :owner AND #st = :inProgress")
              .expressionAttributeNames(Map.of("#st", "status", "#owner", "owner"))
              .expressionAttributeValues(
                  Map.of(
                      ":processed", s("PROCESSED"),
                      ":at", n(at.toEpochMilli()),
                      ":owner", s(owner),
                      ":inProgress", s("IN_PROGRESS")))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false; // another delivery owns it now
    } catch (SdkException e) {
      throw new InboxException("DynamoDB inbox update failed", e);
    }
  }

  @Override
  public boolean release(String consumer, String messageId, String owner) {
    try {
      dynamo.deleteItem(
          DeleteItemRequest.builder()
              .tableName(table)
              .key(Map.of("pk", s(key(consumer, messageId))))
              .conditionExpression("#owner = :owner AND #st = :inProgress")
              .expressionAttributeNames(Map.of("#st", "status", "#owner", "owner"))
              .expressionAttributeValues(
                  Map.of(":inProgress", s("IN_PROGRESS"), ":owner", s(owner)))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false; // processed, or owned by another delivery: keep it
    } catch (SdkException e) {
      throw new InboxException("DynamoDB inbox release failed", e);
    }
  }

  /** DynamoDB TTL removes expired items; nothing to do. */
  @Override
  public int purgeExpired(Instant now) {
    return 0;
  }

  static String key(String consumer, String messageId) {
    return consumer + "#" + messageId;
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  private static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }
}
