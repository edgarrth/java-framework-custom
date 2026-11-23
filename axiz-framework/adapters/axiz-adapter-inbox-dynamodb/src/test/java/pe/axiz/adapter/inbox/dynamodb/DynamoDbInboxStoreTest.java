package pe.axiz.adapter.inbox.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.core.inbox.InboxStore;
import pe.axiz.core.inbox.InboxStore.Acquisition;
import pe.axiz.test.contracts.InboxStoreContract;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbInboxStoreTest {

  /** Evalúa contra una tabla en memoria las condiciones que usa este almacén. */
  static final class FakeDynamo implements DynamoDbClient {
    final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    private static boolean owned(Map<String, AttributeValue> item, Map<String, AttributeValue> v) {
      return item != null
          && "IN_PROGRESS".equals(item.get("status").s())
          && v.get(":owner").s().equals(item.get("owner").s());
    }

    @Override
    public PutItemResponse putItem(PutItemRequest r) {
      String pk = r.item().get("pk").s();
      Map<String, AttributeValue> existing = items.get(pk);
      long now = Long.parseLong(r.expressionAttributeValues().get(":now").n());
      if (existing != null
          && !("IN_PROGRESS".equals(existing.get("status").s())
              && Long.parseLong(existing.get("leaseUntil").n()) < now)) {
        throw ConditionalCheckFailedException.builder().message("exists").item(existing).build();
      }
      items.put(pk, new HashMap<>(r.item()));
      return PutItemResponse.builder().build();
    }

    @Override
    public UpdateItemResponse updateItem(UpdateItemRequest r) {
      Map<String, AttributeValue> item = items.get(r.key().get("pk").s());
      if (!owned(item, r.expressionAttributeValues())
          || (r.conditionExpression().contains("attribute_exists(expiresAt)")
              && !item.containsKey("expiresAt"))
          || (r.conditionExpression().contains("leaseUntil > :at")
              && Long.parseLong(item.get("leaseUntil").n())
                  <= Long.parseLong(r.expressionAttributeValues().get(":at").n()))) {
        throw ConditionalCheckFailedException.builder().message("not owner").build();
      }
      item.put("status", r.expressionAttributeValues().get(":processed"));
      item.put("ttl", item.get("expiresAt"));
      return UpdateItemResponse.builder().build();
    }

    @Override
    public DeleteItemResponse deleteItem(DeleteItemRequest r) {
      Map<String, AttributeValue> existing = items.get(r.key().get("pk").s());
      if (!owned(existing, r.expressionAttributeValues())) {
        throw ConditionalCheckFailedException.builder().message("not owner").build();
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
  void conditionalAcquisitionWithOwners() {
    FakeDynamo dynamo = new FakeDynamo();
    DynamoDbInboxStore store = new DynamoDbInboxStore(dynamo, "axiz-inbox");
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    assertEquals(
        Acquisition.Status.ACQUIRED,
        store
            .tryAcquire("c", "e-1", "a", now, now.plusSeconds(60), now.plusSeconds(3600))
            .status());
    Acquisition busy =
        store.tryAcquire("c", "e-1", "b", now, now.plusSeconds(60), now.plusSeconds(3600));
    assertEquals(Acquisition.Status.BUSY, busy.status());
    assertEquals(now.plusSeconds(60), busy.leaseUntil());
    // el lease de A expira y B toma el control
    assertEquals(
        Acquisition.Status.ACQUIRED,
        store
            .tryAcquire(
                "c", "e-1", "b", now.plusSeconds(61), now.plusSeconds(120), now.plusSeconds(3600))
            .status());
    assertFalse(store.markProcessed("c", "e-1", "a", now), "late owner A");
    assertFalse(store.release("c", "e-1", "a"), "late owner A");
    assertTrue(store.markProcessed("c", "e-1", "b", now));
    assertFalse(store.release("c", "e-1", "b"), "processed entries are kept");
    assertEquals(
        Acquisition.Status.PROCESSED,
        store
            .tryAcquire(
                "c", "e-1", "c", now.plusSeconds(500), now.plusSeconds(560), now.plusSeconds(9000))
            .status());
    assertEquals(0, store.purgeExpired(now));
    assertEquals("c#e-1", DynamoDbInboxStore.key("c", "e-1"));
  }

  @Test
  void retentionTtlIsEnabledOnlyAfterCompletionAndMissingMigrationFailsClosed() {
    var dynamo = new FakeDynamo();
    var store = new DynamoDbInboxStore(dynamo, "axiz-inbox");
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    store.tryAcquire("ttl", "event", "owner", now, now.plusSeconds(60), now.plusSeconds(3600));
    var item = dynamo.items.get("ttl#event");
    assertFalse(item.containsKey("ttl"));
    var expiration = item.remove("expiresAt");
    assertFalse(store.markProcessed("ttl", "event", "owner", now.plusSeconds(1)));
    assertEquals("IN_PROGRESS", item.get("status").s());
    item.put("expiresAt", expiration);
    assertTrue(store.markProcessed("ttl", "event", "owner", now.plusSeconds(1)));
    assertEquals(expiration, item.get("ttl"));
  }

  @TestFactory
  Stream<DynamicTest> contract() {
    InboxStoreContract.Backend backend =
        new InboxStoreContract.Backend() {
          @Override
          public InboxStore create() {
            return new DynamoDbInboxStore(new FakeDynamo(), "axiz-inbox");
          }

          @Override
          public boolean purgesOnDemand() {
            return false; // TTL de la tabla
          }
        };
    return InboxStoreContract.checks(backend).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }

  /** Responde cada escritura con el fallo indicado. */
  static DynamoDbClient failing(RuntimeException failure) {
    return new DynamoDbClient() {
      @Override
      public PutItemResponse putItem(PutItemRequest r) {
        throw failure;
      }

      @Override
      public UpdateItemResponse updateItem(UpdateItemRequest r) {
        throw failure;
      }

      @Override
      public DeleteItemResponse deleteItem(DeleteItemRequest r) {
        throw failure;
      }

      @Override
      public String serviceName() {
        return "dynamodb";
      }

      @Override
      public void close() {}
    };
  }

  @Test
  void aRefusedWriteWithoutTheCurrentItemIsBusyWithAnUnknownLease() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    // la condición falló pero DynamoDB no devolvió ítem (o uno sin lease): ocupado, lease
    // desconocido
    DynamoDbInboxStore withoutItem =
        new DynamoDbInboxStore(
            failing(ConditionalCheckFailedException.builder().message("exists").build()), "t");
    Acquisition busy =
        withoutItem.tryAcquire("c", "e-1", "a", now, now.plusSeconds(60), now.plusSeconds(3600));
    assertEquals(Acquisition.Status.BUSY, busy.status());
    assertNull(busy.leaseUntil());

    DynamoDbInboxStore withoutLease =
        new DynamoDbInboxStore(
            failing(
                ConditionalCheckFailedException.builder()
                    .message("exists")
                    .item(Map.of("status", AttributeValue.fromS("IN_PROGRESS")))
                    .build()),
            "t");
    assertNull(
        withoutLease
            .tryAcquire("c", "e-1", "a", now, now.plusSeconds(60), now.plusSeconds(3600))
            .leaseUntil());
  }

  @Test
  void dynamoFailuresAreInboxExceptions() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    SdkException down = SdkException.builder().message("unreachable").build();
    DynamoDbInboxStore store = new DynamoDbInboxStore(failing(down), "t");
    InboxException acquire =
        assertThrows(
            InboxException.class,
            () ->
                store.tryAcquire("c", "e-1", "a", now, now.plusSeconds(60), now.plusSeconds(120)));
    assertInstanceOf(SdkException.class, acquire.getCause());
    assertThrows(InboxException.class, () -> store.markProcessed("c", "e-1", "a", now));
    assertThrows(InboxException.class, () -> store.release("c", "e-1", "a"));
  }
}
