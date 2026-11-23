package pe.axiz.adapter.sequence.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.sequence.AsyncSequenceStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Asignación atómica asíncrona nativa de bloques, que comparte los registros de secuencia
 * síncronos.
 */
public final class DynamoDbAsyncSequenceStore implements AsyncSequenceStore {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "DynamoDbAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final DynamoDbAsyncClient dynamo;

  private final String table;

  public DynamoDbAsyncSequenceStore(DynamoDbAsyncClient dynamo, String table) {
    this.dynamo = Objects.requireNonNull(dynamo);
    this.table = Objects.requireNonNull(table);
  }

  @Override
  public CompletionStage<Long> increment(String name, String period, int increment) {
    if (increment <= 0) throw new IllegalArgumentException("increment must be positive");
    return Stages.map(
        dynamo.updateItem(
            UpdateItemRequest.builder()
                .tableName(table)
                .key(Map.of("pk", AttributeValue.builder().s(name + "#" + period).build()))
                .updateExpression("ADD seqValue :inc")
                .expressionAttributeValues(
                    Map.of(":inc", AttributeValue.builder().n(Integer.toString(increment)).build()))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build()),
        response -> {
          AttributeValue value = response.attributes().get("seqValue");
          if (value == null || value.n() == null)
            throw new IllegalStateException("DynamoDB did not return the new sequence value");
          return Long.parseLong(value.n());
        });
  }
}
