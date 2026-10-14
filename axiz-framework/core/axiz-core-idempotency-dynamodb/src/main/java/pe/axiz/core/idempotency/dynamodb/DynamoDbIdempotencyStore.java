package pe.axiz.core.idempotency.dynamodb;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.core.idempotency.StoredResponseCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Idempotency store on DynamoDB: conditional put to start (new key or expired record), strongly
 * consistent reads, TTL attribute {@code ttl} for retention. Completion and release are conditioned
 * on {@code status = IN_PROGRESS AND owner = :owner}, so an execution whose lock expired cannot
 * touch the record of the next owner.
 */
public final class DynamoDbIdempotencyStore implements IdempotencyStore {

  static final String HELD_BY_OWNER = "#st = :inProgress AND #owner = :owner";

  private final DynamoDbClient dynamo;
  private final String table;
  private final Clock clock;

  public DynamoDbIdempotencyStore(DynamoDbClient dynamo, String table) {
    this(dynamo, table, Clock.systemUTC());
  }

  public DynamoDbIdempotencyStore(DynamoDbClient dynamo, String table, Clock clock) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public boolean tryStart(IdempotencyRecord record) {
    try {
      dynamo.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(item(record))
              .conditionExpression("attribute_not_exists(pk) OR expiresAtMs < :now")
              .expressionAttributeValues(Map.of(":now", n(clock.millis())))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public Optional<IdempotencyRecord> find(String key, Instant now) {
    Map<String, AttributeValue> item =
        dynamo
            .getItem(
                GetItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("pk", s(key)))
                    .consistentRead(true)
                    .build())
            .item();
    if (item == null || item.isEmpty() || !item.containsKey("data")) {
      return Optional.empty();
    }
    IdempotencyRecord r = StoredResponseCodec.read(key, item.get("data").s());
    return r.expiresAt().isBefore(now) ? Optional.empty() : Optional.of(r);
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    Optional<IdempotencyRecord> current = find(key, clock.instant());
    if (current.isEmpty() || !current.get().heldBy(owner)) {
      return false;
    }
    IdempotencyRecord done =
        new IdempotencyRecord(
            key,
            current.get().fingerprint(),
            IdempotencyRecord.Status.COMPLETED,
            owner,
            response,
            expiresAt);
    try {
      dynamo.putItem(
          PutItemRequest.builder()
              .tableName(table)
              .item(item(done))
              .conditionExpression(HELD_BY_OWNER)
              .expressionAttributeNames(names())
              .expressionAttributeValues(values(owner))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  @Override
  public boolean release(String key, String owner) {
    try {
      dynamo.deleteItem(
          DeleteItemRequest.builder()
              .tableName(table)
              .key(Map.of("pk", s(key)))
              .conditionExpression(HELD_BY_OWNER)
              .expressionAttributeNames(names())
              .expressionAttributeValues(values(owner))
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      // completed records are kept for replay; records of other owners are not ours
      return false;
    }
  }

  private static Map<String, String> names() {
    return Map.of("#st", "status", "#owner", "owner");
  }

  private static Map<String, AttributeValue> values(String owner) {
    return Map.of(
        ":inProgress", s(IdempotencyRecord.Status.IN_PROGRESS.name()), ":owner", s(owner));
  }

  private static Map<String, AttributeValue> item(IdempotencyRecord r) {
    Map<String, AttributeValue> item = new HashMap<>();
    item.put("pk", s(r.key()));
    item.put("status", s(r.status().name()));
    if (r.owner() != null) {
      item.put("owner", s(r.owner()));
    }
    item.put("data", s(StoredResponseCodec.write(r)));
    item.put("expiresAtMs", n(r.expiresAt().toEpochMilli()));
    item.put("ttl", n(r.expiresAt().getEpochSecond()));
    return item;
  }

  private static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  private static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }
}
