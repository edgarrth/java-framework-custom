package pe.axiz.adapter.idempotency.dynamodb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.core.idempotency.IdempotencyRecord;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.test.contracts.AsyncIdempotencyProbe;
import pe.axiz.test.contracts.IdempotencyStoreContract;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class DynamoDbIdempotencyStoreTest {

  /** Evaluates the two condition expressions used by the store. */
  static final class FakeDynamo implements DynamoDbClient {
    final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    private static boolean heldBy(
        Map<String, AttributeValue> item, Map<String, AttributeValue> values) {
      return item != null
          && values.get(":inProgress").s().equals(item.get("status").s())
          && item.containsKey("owner")
          && values.get(":owner").s().equals(item.get("owner").s());
    }

    @Override
    public synchronized PutItemResponse putItem(PutItemRequest r) {
      String pk = r.item().get("pk").s();
      Map<String, AttributeValue> existing = items.get(pk);
      String condition = r.conditionExpression();
      if (condition != null && condition.startsWith("attribute_not_exists")) {
        if (existing != null
            && Long.parseLong(existing.get("expiresAtMs").n())
                >= Long.parseLong(r.expressionAttributeValues().get(":now").n())) {
          throw ConditionalCheckFailedException.builder().message("exists").build();
        }
      } else if (condition != null && condition.contains("#data = :expected")) {
        if (existing == null
            || !existing.get("data").s().equals(r.expressionAttributeValues().get(":expected").s())
            || Long.parseLong(existing.get("expiresAtMs").n())
                <= Long.parseLong(r.expressionAttributeValues().get(":now").n()))
          throw ConditionalCheckFailedException.builder().message("record changed").build();
      } else if (DynamoDbIdempotencyStore.HELD_BY_OWNER.equals(condition)) {
        if (!heldBy(existing, r.expressionAttributeValues())) {
          throw ConditionalCheckFailedException.builder().message("not owner").build();
        }
      } else if (condition != null) {
        throw new UnsupportedOperationException(condition);
      }
      items.put(pk, r.item());
      return PutItemResponse.builder().build();
    }

    @Override
    public synchronized GetItemResponse getItem(GetItemRequest r) {
      Map<String, AttributeValue> item = items.get(r.key().get("pk").s());
      return item == null
          ? GetItemResponse.builder().build()
          : GetItemResponse.builder().item(item).build();
    }

    @Override
    public synchronized DeleteItemResponse deleteItem(DeleteItemRequest r) {
      String pk = r.key().get("pk").s();
      if (r.conditionExpression().contains("#data = :expected")) {
        var existing = items.get(pk);
        if (existing == null
            || !existing.get("data").s().equals(r.expressionAttributeValues().get(":expected").s())
            || Long.parseLong(existing.get("expiresAtMs").n())
                <= Long.parseLong(r.expressionAttributeValues().get(":now").n()))
          throw ConditionalCheckFailedException.builder().message("record changed").build();
        items.remove(pk);
        return DeleteItemResponse.builder().build();
      }
      if (!DynamoDbIdempotencyStore.HELD_BY_OWNER.equals(r.conditionExpression())
          || !heldBy(items.get(pk), r.expressionAttributeValues())) {
        throw ConditionalCheckFailedException.builder().message("no").build();
      }
      items.remove(pk);
      return DeleteItemResponse.builder().build();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}
  }

  private static IdempotencyRecord inProgress(String owner, Instant exp) {
    return IdempotencyRecord.builder("k", "fp", exp, exp)
        .status(IdempotencyRecord.Status.IN_PROGRESS)
        .owner(owner)
        .response(null)
        .resolutionVersion(0)
        .failure(null)
        .build();
  }

  @Test
  void lifecycle() {
    FakeDynamo dynamo = new FakeDynamo();
    DynamoDbIdempotencyStore store = new DynamoDbIdempotencyStore(dynamo, "axiz-idempotency");
    Instant exp = Instant.now().plusSeconds(60);
    assertTrue(store.tryStart(inProgress("a", exp)));
    assertFalse(store.tryStart(inProgress("b", exp)));
    assertTrue(store.release("k", "a"));
    assertTrue(store.tryStart(inProgress("a", exp)));
    assertTrue(store.complete("k", "a", new StoredResponse(201, Map.of(), new byte[] {1}), exp));
    assertEquals(201, store.find("k", Instant.now()).orElseThrow().response().status());
    assertFalse(store.release("k", "a"));
    assertTrue(store.find("k", Instant.now()).isPresent());
    assertTrue(store.find("k", exp.plusSeconds(1)).isEmpty());
    assertTrue(store.find("none", Instant.now()).isEmpty());
  }

  /** Regression: A's lock expires, B takes the key, A finishes late. */
  @Test
  void aLateOwnerCannotOverwriteOrReleaseTheKeyOfTheNewOwner() {
    FakeDynamo dynamo = new FakeDynamo();
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    DynamoDbIdempotencyStore atT0 =
        new DynamoDbIdempotencyStore(dynamo, "t", Clock.fixed(t0, ZoneOffset.UTC));
    DynamoDbIdempotencyStore later =
        new DynamoDbIdempotencyStore(dynamo, "t", Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
    assertTrue(atT0.tryStart(inProgress("owner-a", t0.plusSeconds(30))));
    assertTrue(later.tryStart(inProgress("owner-b", t0.plusSeconds(90))), "expired lock is taken");

    Instant retention = t0.plusSeconds(3600);
    assertFalse(
        later.complete(
            "k", "owner-a", new StoredResponse(201, Map.of(), new byte[] {'A'}), retention));
    assertFalse(later.release("k", "owner-a"));
    assertEquals("owner-b", later.find("k", t0.plusSeconds(60)).orElseThrow().owner());

    assertTrue(
        later.complete(
            "k", "owner-b", new StoredResponse(201, Map.of(), new byte[] {'B'}), retention));
    assertArrayEquals(
        new byte[] {'B'}, later.find("k", t0.plusSeconds(60)).orElseThrow().response().body());
  }

  @TestFactory
  Stream<DynamicTest> contract() {
    return IdempotencyStoreContract.checks(
            clock -> new DynamoDbIdempotencyStore(new FakeDynamo(), "axiz-idempotency", clock))
        .stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  @TestFactory
  @SuppressWarnings("unchecked")
  Stream<DynamicTest> nativeAsyncContract() {
    return IdempotencyStoreContract.checks(
            clock ->
                new AsyncIdempotencyProbe(
                    new DynamoDbAsyncIdempotencyStore(
                        ImmediateAsyncFakes.adapt(DynamoDbAsyncClient.class, new FakeDynamo()),
                        "axiz-idempotency",
                        clock)))
        .stream()
        .map(check -> DynamicTest.dynamicTest("async: " + check.name(), check::run));
  }
}
