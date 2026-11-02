package pe.axiz.adapter.sequence.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbAsyncSequenceStoreTest {
  @Test
  void usesAtomicAddAndKeepsPeriodsAndNamesIndependent() {
    Map<String, Long> counters = new HashMap<>();
    DynamoDbClient protocol =
        new DynamoDbClient() {
          @Override
          public UpdateItemResponse updateItem(UpdateItemRequest request) {
            assertEquals("stable-sequences", request.tableName());
            assertEquals("ADD seqValue :inc", request.updateExpression());
            assertEquals(ReturnValue.UPDATED_NEW, request.returnValues());
            long value =
                counters.merge(
                    request.key().get("pk").s(),
                    Long.parseLong(request.expressionAttributeValues().get(":inc").n()),
                    Long::sum);
            return UpdateItemResponse.builder()
                .attributes(
                    Map.of("seqValue", AttributeValue.builder().n(Long.toString(value)).build()))
                .build();
          }

          @Override
          public String serviceName() {
            return "dynamodb";
          }

          @Override
          public void close() {}
        };
    var store =
        new DynamoDbAsyncSequenceStore(
            ImmediateAsyncFakes.adapt(DynamoDbAsyncClient.class, protocol), "stable-sequences");
    assertEquals(1L, store.increment("orders", "20261030", 1).toCompletableFuture().join());
    assertEquals(11L, store.increment("orders", "20261030", 10).toCompletableFuture().join());
    assertEquals(1L, store.increment("orders", "20261031", 1).toCompletableFuture().join());
    assertEquals(1L, store.increment("transfers", "20261030", 1).toCompletableFuture().join());
    assertThrows(IllegalArgumentException.class, () -> store.increment("orders", "20261030", 0));
  }

  @Test
  void missingAndNonNumericRepliesNeverBecomeAValidSequence() {
    for (Map<String, AttributeValue> attributes :
        java.util.List.of(
            Map.<String, AttributeValue>of(),
            Map.of("seqValue", AttributeValue.builder().s("7").build()),
            Map.of("seqValue", AttributeValue.builder().n("not-a-number").build()))) {
      var store =
          new DynamoDbAsyncSequenceStore(
              ImmediateAsyncFakes.adapt(
                  DynamoDbAsyncClient.class, DynamoDbSequenceStoreTest.answering(attributes)),
              "sequences");
      assertThrows(
          CompletionException.class,
          () -> store.increment("orders", "20261030", 1).toCompletableFuture().join());
    }
  }

  @Test
  void cancellationAndInfrastructureErrorsReachTheNativeFuture() {
    var future = new CompletableFuture<UpdateItemResponse>();
    var nativeClient =
        (DynamoDbAsyncClient)
            java.lang.reflect.Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbAsyncClient.class},
                (proxy, method, arguments) -> future);
    var store = new DynamoDbAsyncSequenceStore(nativeClient, "sequences");
    var cancelled = store.increment("orders", "20261030", 1).toCompletableFuture();
    cancelled.cancel(true);
    assertTrue(future.isCancelled());
    var expected = new IllegalStateException("backend offline");
    var failed =
        (DynamoDbAsyncClient)
            java.lang.reflect.Proxy.newProxyInstance(
                DynamoDbAsyncClient.class.getClassLoader(),
                new Class<?>[] {DynamoDbAsyncClient.class},
                (proxy, method, arguments) -> CompletableFuture.failedFuture(expected));
    var operation =
        new DynamoDbAsyncSequenceStore(failed, "sequences").increment("orders", "20261030", 1);
    assertEquals(
        expected,
        assertThrows(CompletionException.class, () -> operation.toCompletableFuture().join())
            .getCause());
  }
}
