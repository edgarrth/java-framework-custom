package pe.axiz.adapter.lock.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * Lock sobre escrituras condicionales de DynamoDB: se adquiere cuando el ítem no existe o su lease
 * expiró; se renueva y libera solo con el token del propietario. El atributo {@code ttl} limpia los
 * ítems abandonados.
 */
public final class DynamoDbDistributedLock implements DistributedLock {

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbClient is an injected collaborator whose identity and lifecycle must be "
              + "shared, not copied.")
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
              // lock tomado por otro después de la expiración
            }
          }
        });
  }

  static AttributeValue s(String v) {
    return AttributeValue.builder().s(v).build();
  }

  static AttributeValue n(long v) {
    return AttributeValue.builder().n(Long.toString(v)).build();
  }
}
