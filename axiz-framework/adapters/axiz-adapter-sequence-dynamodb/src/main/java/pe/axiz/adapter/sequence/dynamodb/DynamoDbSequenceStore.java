package pe.axiz.adapter.sequence.dynamodb;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sequence.SequenceStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** Atomic counter on DynamoDB ({@code ADD value :inc} with {@code UPDATED_NEW}). */
public final class DynamoDbSequenceStore implements SequenceStore {

  private final DynamoDbClient dynamo;
  private final String table;

  public DynamoDbSequenceStore(DynamoDbClient dynamo, String table) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
  }

  @Override
  public long increment(String name, String period, int increment) {
    var response =
        dynamo.updateItem(
            UpdateItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", AttributeValue.builder().s(name + "#" + period).build()))
                .updateExpression("ADD seqValue :inc")
                .expressionAttributeValues(
                    Map.of(":inc", AttributeValue.builder().n(Integer.toString(increment)).build()))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());
    AttributeValue value = response.attributes().get("seqValue");
    @Nullable String number = value == null ? null : value.n();
    if (number == null) {
      throw new IllegalStateException(
          "DynamoDB did not return the new value of sequence " + name + " (" + period + ")");
    }
    return Long.parseLong(number);
  }
}
