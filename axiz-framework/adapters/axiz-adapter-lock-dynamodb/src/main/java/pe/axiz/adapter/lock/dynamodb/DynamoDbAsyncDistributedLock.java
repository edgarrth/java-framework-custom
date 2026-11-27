package pe.axiz.adapter.lock.dynamodb;

import static pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock.n;
import static pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock.s;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.lock.AsyncDistributedLock;
import pe.axiz.core.lock.AsyncLockHandle;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Leases asíncronos nativos con claves, expiración y fencing por propietario idénticos al almacén
 * síncrono.
 */
public final class DynamoDbAsyncDistributedLock implements AsyncDistributedLock {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final DynamoDbAsyncClient dynamo;

  private final String table;
  private final Clock clock;

  public DynamoDbAsyncDistributedLock(DynamoDbAsyncClient dynamo, String table, Clock clock) {
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
  public CompletionStage<Optional<AsyncLockHandle>> tryAcquire(String resource, Duration lease) {
    if (!lease.isPositive()) throw new IllegalArgumentException("lease must be positive");
    String token = UUID.randomUUID().toString();
    long now = clock.millis();
    long until = Math.addExact(now, lease.toMillis());
    return Stages.map(
        conditional(
            dynamo.putItem(
                PutItemRequest.builder()
                    .tableName(table)
                    .item(
                        Map.of(
                            "pk",
                            s(resource),
                            "token",
                            s(token),
                            "leaseUntil",
                            n(until),
                            "ttl",
                            n(until / 1000 + 3600)))
                    .conditionExpression("attribute_not_exists(pk) OR leaseUntil < :now")
                    .expressionAttributeValues(Map.of(":now", n(now)))
                    .build())),
        acquired -> {
          if (!acquired) return Optional.empty();
          return Optional.of(
              new AsyncLockHandle() {
                @Override
                public String resource() {
                  return resource;
                }

                @Override
                public String token() {
                  return token;
                }

                @Override
                public CompletionStage<Boolean> renew(Duration nextLease) {
                  if (!nextLease.isPositive())
                    throw new IllegalArgumentException("lease must be positive");
                  long deadline = Math.addExact(clock.millis(), nextLease.toMillis());
                  return conditional(
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
                                      n(deadline),
                                      ":ttl",
                                      n(deadline / 1000 + 3600),
                                      ":token",
                                      s(token)))
                              .build()));
                }

                @Override
                public CompletionStage<Void> release() {
                  return Stages.ignore(
                      conditional(
                          dynamo.deleteItem(
                              DeleteItemRequest.builder()
                                  .tableName(table)
                                  .key(Map.of("pk", s(resource)))
                                  .conditionExpression("#tk = :token")
                                  .expressionAttributeNames(Map.of("#tk", "token"))
                                  .expressionAttributeValues(Map.of(":token", s(token)))
                                  .build())));
                }
              });
        });
  }
}
