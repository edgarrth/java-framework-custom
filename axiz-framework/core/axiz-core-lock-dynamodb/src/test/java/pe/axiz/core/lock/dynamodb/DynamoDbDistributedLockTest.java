package pe.axiz.core.lock.dynamodb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbDistributedLockTest {

  static final class FakeDynamo implements DynamoDbClient {
    final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    private static ConditionalCheckFailedException failed() {
      return ConditionalCheckFailedException.builder().message("condition").build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest r) {
      String pk = r.item().get("pk").s();
      var existing = items.get(pk);
      long now = Long.parseLong(r.expressionAttributeValues().get(":now").n());
      if (existing != null && Long.parseLong(existing.get("leaseUntil").n()) >= now) {
        throw failed();
      }
      items.put(pk, new HashMap<>(r.item()));
      return PutItemResponse.builder().build();
    }

    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest r) {
      var existing = items.get(r.key().get("pk").s());
      if (existing == null
          || !existing.get("token").s().equals(r.expressionAttributeValues().get(":token").s())) {
        throw failed();
      }
      existing.put("leaseUntil", r.expressionAttributeValues().get(":until"));
      return UpdateItemResponse.builder().build();
    }

    @Override
    public DeleteItemResponse deleteItem(DeleteItemRequest r) {
      var existing = items.get(r.key().get("pk").s());
      if (existing == null
          || !existing.get("token").s().equals(r.expressionAttributeValues().get(":token").s())) {
        throw failed();
      }
      items.remove(r.key().get("pk").s());
      return DeleteItemResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}
  }

  @Test
  void leaseAndOwnership() {
    FakeDynamo dynamo = new FakeDynamo();
    Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    DynamoDbDistributedLock lock = new DynamoDbDistributedLock(dynamo, "axiz-locks", clock);
    var a = lock.tryAcquire("account:1", Duration.ofSeconds(10)).orElseThrow();
    assertTrue(lock.tryAcquire("account:1", Duration.ofSeconds(10)).isEmpty());
    assertTrue(a.renew(Duration.ofSeconds(10)));
    now[0] = now[0].plusSeconds(11);
    var b = lock.tryAcquire("account:1", Duration.ofSeconds(10)).orElseThrow();
    assertFalse(a.renew(Duration.ofSeconds(10)));
    a.release();
    assertTrue(dynamo.items.containsKey("account:1"));
    b.release();
    assertFalse(dynamo.items.containsKey("account:1"));
  }
}
