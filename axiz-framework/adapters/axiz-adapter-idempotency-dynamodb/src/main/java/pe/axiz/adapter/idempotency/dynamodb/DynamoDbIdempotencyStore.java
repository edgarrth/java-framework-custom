package pe.axiz.adapter.idempotency.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
 * Almacén de idempotencia sobre DynamoDB: put condicional para iniciar (clave nueva o registro
 * expirado), lecturas fuertemente consistentes y atributo TTL {@code ttl} para la retención. La
 * finalización y la liberación se condicionan a {@code status = IN_PROGRESS AND owner = :owner}, de
 * modo que una ejecución cuyo lock expiró no puede tocar el registro del siguiente propietario.
 */
public final class DynamoDbIdempotencyStore implements IdempotencyStore {

  static final String HELD_BY_OWNER = "#st = :inProgress AND #owner = :owner";

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbClient is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
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
  public boolean compareAndSet(
      IdempotencyRecord expected, @Nullable IdempotencyRecord replacement) {
    if (replacement != null && !expected.key().equals(replacement.key()))
      throw new IllegalArgumentException("different record key");
    try {
      if (replacement == null)
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
                .build());
      else
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
                .build());
      return true;
    } catch (ConditionalCheckFailedException conflict) {
      return false;
    }
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
    return !r.liveAt(now) ? Optional.empty() : Optional.of(r);
  }

  @Override
  public boolean complete(String key, String owner, StoredResponse response, Instant expiresAt) {
    for (int attempt = 0; attempt < 16; attempt++) {
      Optional<IdempotencyRecord> current = find(key, clock.instant());
      if (current.isEmpty() || !current.get().heldBy(owner)) return false;
      if (compareAndSet(current.get(), current.get().completed(response, expiresAt))) return true;
    }
    return false;
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
      // los registros completados se conservan para replay; los registros de otros propietarios no
      // son propios
      return false;
    }
  }

  static Map<String, String> names() {
    return Map.of("#st", "status", "#owner", "owner");
  }

  static Map<String, AttributeValue> values(String owner) {
    return Map.of(
        ":inProgress", s(IdempotencyRecord.Status.IN_PROGRESS.name()), ":owner", s(owner));
  }

  static Map<String, AttributeValue> item(IdempotencyRecord r) {
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

  static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }
}
