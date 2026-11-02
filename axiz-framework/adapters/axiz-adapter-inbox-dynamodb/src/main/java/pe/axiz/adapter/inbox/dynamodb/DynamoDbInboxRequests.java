package pe.axiz.adapter.inbox.dynamodb;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** Shared wire conditions: asynchronous and poll-thread boundaries cannot diverge in ownership. */
final class DynamoDbInboxRequests {
  private final String table;

  DynamoDbInboxRequests(String table) {
    this.table = Objects.requireNonNull(table);
  }

  PutItemRequest acquire(
      String consumer,
      String messageId,
      String owner,
      Instant now,
      Instant leaseUntil,
      Instant expiresAt) {
    return PutItemRequest.builder()
        .tableName(table)
        .item(
            Map.of(
                "pk",
                s(DynamoDbInboxStore.key(consumer, messageId)),
                "status",
                s("IN_PROGRESS"),
                "owner",
                s(owner),
                "leaseUntil",
                n(leaseUntil.toEpochMilli()),
                "ttl",
                n(expiresAt.getEpochSecond())))
        .conditionExpression(
            "attribute_not_exists(pk) OR (#st = :inProgress AND leaseUntil < :now)")
        .expressionAttributeNames(Map.of("#st", "status"))
        .expressionAttributeValues(
            Map.of(":inProgress", s("IN_PROGRESS"), ":now", n(now.toEpochMilli())))
        .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
        .build();
  }

  UpdateItemRequest processed(String consumer, String messageId, String owner, Instant at) {
    return UpdateItemRequest.builder()
        .tableName(table)
        .key(Map.of("pk", s(DynamoDbInboxStore.key(consumer, messageId))))
        .updateExpression("SET #st = :processed, processedAt = :at")
        .conditionExpression("#owner = :owner AND #st = :inProgress")
        .expressionAttributeNames(Map.of("#st", "status", "#owner", "owner"))
        .expressionAttributeValues(
            Map.of(
                ":processed",
                s("PROCESSED"),
                ":at",
                n(at.toEpochMilli()),
                ":owner",
                s(owner),
                ":inProgress",
                s("IN_PROGRESS")))
        .build();
  }

  DeleteItemRequest release(String consumer, String messageId, String owner) {
    return DeleteItemRequest.builder()
        .tableName(table)
        .key(Map.of("pk", s(DynamoDbInboxStore.key(consumer, messageId))))
        .conditionExpression("#owner = :owner AND #st = :inProgress")
        .expressionAttributeNames(Map.of("#st", "status", "#owner", "owner"))
        .expressionAttributeValues(Map.of(":inProgress", s("IN_PROGRESS"), ":owner", s(owner)))
        .build();
  }

  static Acquisition current(ConditionalCheckFailedException failure) {
    Map<String, AttributeValue> current = failure.hasItem() ? failure.item() : Map.of();
    AttributeValue status = current.get("status");
    if (status != null && "PROCESSED".equals(status.s())) return Acquisition.processed();
    AttributeValue until = current.get("leaseUntil");
    return Acquisition.busy(until == null ? null : Instant.ofEpochMilli(Long.parseLong(until.n())));
  }

  static RuntimeException failure(String operation, Throwable failure) {
    if (failure instanceof SdkException)
      return new InboxException("DynamoDB inbox " + operation + " failed", failure);
    if (failure instanceof RuntimeException runtime) return runtime;
    if (failure instanceof Error error) throw error;
    return new InboxException("DynamoDB inbox " + operation + " failed", failure);
  }

  private static AttributeValue s(String value) {
    return AttributeValue.fromS(value);
  }

  private static AttributeValue n(long value) {
    return AttributeValue.fromN(Long.toString(value));
  }
}
