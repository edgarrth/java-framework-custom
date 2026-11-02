package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticTableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoAsyncVersionTest {
  public static final class Versioned {
    private String id = "";
    private Long version;

    public String getId() {
      return id;
    }

    public void setId(String value) {
      id = value;
    }

    public Long getVersion() {
      return version;
    }

    public void setVersion(Long value) {
      version = value;
    }
  }

  @Test
  void sdkOptimisticLockingIsPreservedForCreatesAndUpdates() {
    var schema =
        StaticTableSchema.builder(Versioned.class)
            .newItemSupplier(Versioned::new)
            .addAttribute(
                String.class,
                a ->
                    a.name("pk")
                        .getter(Versioned::getId)
                        .setter(Versioned::setId)
                        .tags(StaticAttributeTags.primaryPartitionKey()))
            .addAttribute(
                Long.class,
                a ->
                    a.name("version")
                        .getter(Versioned::getVersion)
                        .setter(Versioned::setVersion)
                        .tags(VersionedRecordExtension.AttributeTags.versionAttribute()))
            .build();
    var raw = new VersionedClient();
    var repository = new DynamoAsyncRepository<>(raw, "versioned", schema);
    var item = new Versioned();
    item.setId("C-1");
    repository.save(item).toCompletableFuture().join();
    assertTrue(raw.put.conditionExpression().contains("attribute_not_exists"));
    assertEquals(AttributeValue.fromN("1"), raw.put.item().get("version"));
    item.setVersion(7L);
    var update = repository.update(item).toCompletableFuture();
    assertTrue(raw.update.expressionAttributeValues().containsValue(AttributeValue.fromN("7")));
    assertTrue(raw.update.expressionAttributeValues().containsValue(AttributeValue.fromN("8")));
    var conflict = ConditionalCheckFailedException.builder().message("stale version").build();
    raw.pending.completeExceptionally(conflict);
    assertSame(conflict, assertThrows(CompletionException.class, update::join).getCause());
  }

  private static final class VersionedClient implements DynamoDbAsyncClient {
    PutItemRequest put = PutItemRequest.builder().build();
    UpdateItemRequest update = UpdateItemRequest.builder().build();
    final CompletableFuture<UpdateItemResponse> pending = new CompletableFuture<>();

    public CompletableFuture<PutItemResponse> putItem(PutItemRequest request) {
      put = request;
      return CompletableFuture.completedFuture(PutItemResponse.builder().build());
    }

    public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest request) {
      update = request;
      return pending;
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {}
  }
}
