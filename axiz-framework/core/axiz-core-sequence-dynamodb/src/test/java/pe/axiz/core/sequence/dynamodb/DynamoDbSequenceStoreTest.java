package pe.axiz.core.sequence.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbSequenceStoreTest {

  @Test
  void addsAndReturnsUpdatedValue() {
    Map<String, Long> counters = new HashMap<>();
    DynamoDbClient dynamo =
        new DynamoDbClient() {
          @Override
          public UpdateItemResponse updateItem(UpdateItemRequest r) {
            long v =
                counters.merge(
                    r.key().get("pk").s(),
                    Long.parseLong(r.expressionAttributeValues().get(":inc").n()),
                    Long::sum);
            return UpdateItemResponse.builder()
                .attributes(
                    Map.of("seqValue", AttributeValue.builder().n(Long.toString(v)).build()))
                .build();
          }

          @Override
          public String serviceName() {
            return "dynamodb";
          }

          @Override
          public void close() {}
        };
    DynamoDbSequenceStore store = new DynamoDbSequenceStore(dynamo, "axiz-sequences");
    assertEquals(1, store.increment("op", "20260131", 1));
    assertEquals(11, store.increment("op", "20260131", 10));
    assertEquals(1, store.increment("op", "20260201", 1));
  }

  private static DynamoDbClient answering(Map<String, AttributeValue> attributes) {
    return new DynamoDbClient() {
      @Override
      public UpdateItemResponse updateItem(UpdateItemRequest r) {
        return UpdateItemResponse.builder().attributes(attributes).build();
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
  void aMissingNewValueIsAnErrorNotAZero() {
    DynamoDbSequenceStore noAttribute = new DynamoDbSequenceStore(answering(Map.of()), "t");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> noAttribute.increment("op", "20260131", 1));
    assertTrue(e.getMessage().contains("op (20260131)"), e.getMessage());

    DynamoDbSequenceStore notANumber =
        new DynamoDbSequenceStore(
            answering(Map.of("seqValue", AttributeValue.builder().s("7").build())), "t");
    assertThrows(IllegalStateException.class, () -> notANumber.increment("op", "20260131", 1));
  }
}
