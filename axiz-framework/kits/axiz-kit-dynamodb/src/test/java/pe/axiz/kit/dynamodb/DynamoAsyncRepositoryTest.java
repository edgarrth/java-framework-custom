package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pe.axiz.kit.dynamodb.DynamoRepositoryTest.SCHEMA;
import static pe.axiz.kit.dynamodb.DynamoRepositoryTest.row;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.kit.dynamodb.DynamoRepositoryTest.Beneficiary;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoAsyncRepositoryTest {
  private final Scripted client = new Scripted();
  private final DynamoAsyncRepository<Beneficiary> repository =
      new DynamoAsyncRepository<>(client, "beneficiaries", SCHEMA);

  @Test
  void consistentReadsMapAbsenceAndFullKeys() {
    var missing = repository.findById("C-1").toCompletableFuture();
    assertFalse(missing.isDone());
    assertTrue(client.last(GetItemRequest.class).consistentRead());
    client.complete(GetItemResponse.builder().build());
    assertTrue(missing.join().isEmpty());
    var found = repository.findById("C-1", "B-1").toCompletableFuture();
    assertEquals(
        row("C-1", "B-1", "ACTIVE").get("id"), client.last(GetItemRequest.class).key().get("id"));
    client.complete(GetItemResponse.builder().item(row("C-1", "B-1", "ACTIVE")).build());
    assertEquals("B-1", found.join().orElseThrow().getId());
  }

  @Test
  void writesPreserveConditionsUpdatesDeletesAndTransactions() {
    var item = new Beneficiary("C-1", "B-1", "ACTIVE");
    var save = repository.save(item).toCompletableFuture();
    assertNull(client.last(PutItemRequest.class).conditionExpression());
    client.complete(PutItemResponse.builder().build());
    assertNull(save.join());
    var insert = repository.insert(item, "customer").toCompletableFuture();
    assertEquals(
        "attribute_not_exists(#pk)", client.last(PutItemRequest.class).conditionExpression());
    assertEquals(
        Map.of("#pk", "customer"), client.last(PutItemRequest.class).expressionAttributeNames());
    var conflict = ConditionalCheckFailedException.builder().message("exists").build();
    client.pending.completeExceptionally(conflict);
    assertSame(conflict, assertThrows(CompletionException.class, insert::join).getCause());
    var update = repository.update(item).toCompletableFuture();
    assertEquals("beneficiaries", client.last(UpdateItemRequest.class).tableName());
    client.complete(UpdateItemResponse.builder().attributes(row("C-1", "B-1", "BLOCKED")).build());
    assertEquals("BLOCKED", update.join().getStatus());
    var delete = repository.delete("C-1", "B-1").toCompletableFuture();
    assertEquals(
        Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-1")),
        client.last(DeleteItemRequest.class).key());
    client.complete(DeleteItemResponse.builder().build());
    assertNull(delete.join());
    var tx =
        repository
            .transact(
                b ->
                    b.addPutItem(
                        repository.table(),
                        TransactPutItemEnhancedRequest.builder(Beneficiary.class)
                            .item(item)
                            .build()))
            .toCompletableFuture();
    assertEquals(
        "beneficiaries",
        client.last(TransactWriteItemsRequest.class).transactItems().getFirst().put().tableName());
    client.complete(TransactWriteItemsResponse.builder().build());
    assertNull(tx.join());
  }

  @Test
  void cancellationReachesExactNativeRequestsWithoutAffectingOtherCalls() {
    List<java.util.function.Supplier<java.util.concurrent.CompletionStage<?>>> operations =
        List.of(
            () -> repository.findById("C-1"),
            () -> repository.save(new Beneficiary("C-1", "B-1", "ACTIVE")),
            () -> repository.insert(new Beneficiary("C-1", "B-1", "ACTIVE"), "customer"),
            () -> repository.update(new Beneficiary("C-1", "B-1", "ACTIVE")),
            () -> repository.delete("C-1"),
            () -> repository.query("C-1", 2, null),
            () -> repository.queryIndex("by-status", "ACTIVE", 2, null),
            () ->
                repository.transact(
                    b ->
                        b.addPutItem(
                            repository.table(),
                            TransactPutItemEnhancedRequest.builder(Beneficiary.class)
                                .item(new Beneficiary("C-1", "B-1", "ACTIVE"))
                                .build())));
    for (var operation : operations) {
      var result = operation.get().toCompletableFuture();
      var exactNative = client.pending;
      var independent = repository.findById("C-2").toCompletableFuture();
      var independentNative = client.pending;
      assertTrue(result.cancel(true));
      assertTrue(exactNative.isCancelled(), operation.toString());
      assertFalse(independent.isDone());
      assertFalse(independentNative.isDone());
      independent.cancel(false);
    }
    assertFalse(client.closed);
  }

  @Test
  void queryFetchesOnlyOnePageAndRoundTripsCursorAndIndex() {
    var first = repository.query("C-1", 2, null).toCompletableFuture();
    assertEquals(2, client.last(QueryRequest.class).limit());
    var last = Map.of("customer", AttributeValue.fromS("C-1"), "id", AttributeValue.fromS("B-1"));
    client.complete(
        QueryResponse.builder().items(row("C-1", "B-1", "ACTIVE")).lastEvaluatedKey(last).build());
    assertEquals(1, first.join().content().size());
    assertNotNull(first.join().nextCursor());
    assertEquals(1, client.requests.size(), "a continuation must not trigger prefetch");
    var next =
        repository.queryBeginsWith("C-1", "B-", 2, first.join().nextCursor()).toCompletableFuture();
    assertEquals(last, client.last(QueryRequest.class).exclusiveStartKey());
    assertTrue(client.last(QueryRequest.class).keyConditionExpression().contains("begins_with"));
    client.complete(QueryResponse.builder().build());
    assertTrue(next.join().content().isEmpty());
    assertNull(next.join().nextCursor());
    var index = repository.queryIndex("by-status", "ACTIVE", 5, null).toCompletableFuture();
    assertEquals("by-status", client.last(QueryRequest.class).indexName());
    client.complete(QueryResponse.builder().items(row("C-1", "B-1", "ACTIVE")).build());
    assertEquals("B-1", index.join().content().getFirst().getId());
  }

  @Test
  void validationAndNativeSubmissionErrorsAreFailedStages() {
    for (int limit : List.of(0, 501)) {
      var result = repository.query("C-1", limit, null).toCompletableFuture();
      assertInstanceOf(
          IllegalArgumentException.class,
          assertThrows(CompletionException.class, result::join).getCause());
    }
    var invalid = repository.query("C-1", 1, "invalid").toCompletableFuture();
    assertInstanceOf(
        IllegalArgumentException.class,
        assertThrows(CompletionException.class, invalid::join).getCause());
    assertTrue(client.requests.isEmpty());
    var broken =
        new DynamoAsyncRepository<>(
            new DynamoDbAsyncClient() {
              public String serviceName() {
                return "dynamodb";
              }

              public void close() {}

              public CompletableFuture<GetItemResponse> getItem(GetItemRequest request) {
                throw new IllegalStateException("closed");
              }
            },
            "beneficiaries",
            SCHEMA);
    assertInstanceOf(
        IllegalStateException.class,
        assertThrows(
                CompletionException.class,
                () -> broken.findById("C-1").toCompletableFuture().join())
            .getCause());
  }

  private static final class Scripted implements DynamoDbAsyncClient {
    final List<Object> requests = new ArrayList<>();
    CompletableFuture<Object> pending = new CompletableFuture<>();
    boolean closed;

    private <R> CompletableFuture<R> request(Object request) {
      requests.add(request);
      CompletableFuture<R> response = new CompletableFuture<>();
      // Retain the actual SDK-returned future, without a thenApply cancellation boundary.
      pending = cast(response);
      return response;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> cast(CompletableFuture<?> value) {
      return (CompletableFuture<Object>) value;
    }

    void complete(Object value) {
      assertTrue(pending.complete(value));
    }

    <R> R last(Class<R> type) {
      return type.cast(requests.getLast());
    }

    public CompletableFuture<GetItemResponse> getItem(GetItemRequest r) {
      return request(r);
    }

    public CompletableFuture<PutItemResponse> putItem(PutItemRequest r) {
      return request(r);
    }

    public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest r) {
      return request(r);
    }

    public CompletableFuture<DeleteItemResponse> deleteItem(DeleteItemRequest r) {
      return request(r);
    }

    public CompletableFuture<QueryResponse> query(QueryRequest r) {
      return request(r);
    }

    public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
        TransactWriteItemsRequest r) {
      return request(r);
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {
      closed = true;
    }
  }
}
