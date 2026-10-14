package pe.axiz.core.lock.dynamodb;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import pe.axiz.core.lock.DistributedLock;
import pe.axiz.core.lock.LockHandle;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Lock on DynamoDB conditional writes: acquire when the item does not exist or its lease expired;
 * renew and release only with the owner's token. The {@code ttl} attribute cleans abandoned items.
 */
public final class DynamoDbDistributedLock implements DistributedLock {

  private final DynamoDbClient dynamo;
  private final String table;
  private final Clock clock;

  public DynamoDbDistributedLock(DynamoDbClient dynamo, String table, Clock clock) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Optional<LockHandle> tryAcquire(String resource, Duration lease) {
    String token = UUID.randomUUID().toString();
    long now = clock.millis();
    try {
      dynamo.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(
                  Map.of(
                      "pk", s(resource),
                      "token", s(token),
                      "leaseUntil", n(now + lease.toMillis()),
                      "ttl", n((now + lease.toMillis()) / 1000 + 3600)))
              .conditionExpression("attribute_not_exists(pk) OR leaseUntil < :now")
              .expressionAttributeValues(Map.of(":now", n(now)))
              .build());
    } catch (ConditionalCheckFailedException e) {
      return Optional.empty();
    }
    return Optional.of(
        new LockHandle() {
          @Override
          public String resource() {
            return resource;
          }

          @Override
          public String token() {
            return token;
          }

          @Override
          public boolean renew(Duration newLease) {
            long until = clock.millis() + newLease.toMillis();
            try {
              dynamo.updateItem(
                  UpdateItemRequest.builder()
                      .tableName(table)
                      .key(Map.of("pk", s(resource)))
                      .updateExpression("SET leaseUntil = :until, #ttl = :ttl")
                      .conditionExpression("#tk = :token")
                      .expressionAttributeNames(Map.of("#tk", "token", "#ttl", "ttl"))
                      .expressionAttributeValues(
                          Map.of(
                              ":until",
                              n(until),
                              ":ttl",
                              n(until / 1000 + 3600),
                              ":token",
                              s(token)))
                      .build());
              return true;
            } catch (ConditionalCheckFailedException e) {
              return false;
            }
          }

          @Override
          public void release() {
            try {
              dynamo.deleteItem(
                  DeleteItemRequest.builder()
                      .tableName(table)
                      .key(Map.of("pk", s(resource)))
                      .conditionExpression("#tk = :token")
                      .expressionAttributeNames(Map.of("#tk", "token"))
                      .expressionAttributeValues(Map.of(":token", s(token)))
                      .build());
            } catch (ConditionalCheckFailedException e) {
              // lock already taken by someone else after expiry
            }
          }
        });
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  private static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }
}
