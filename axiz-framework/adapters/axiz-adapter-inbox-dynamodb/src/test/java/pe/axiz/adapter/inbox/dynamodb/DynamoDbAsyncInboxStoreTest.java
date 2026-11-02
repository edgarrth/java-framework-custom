package pe.axiz.adapter.inbox.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.inbox.AsyncInboxStore;
import pe.axiz.core.inbox.InboxException;
import pe.axiz.test.contracts.AsyncInboxStoreContract;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class DynamoDbAsyncInboxStoreTest {
  @TestFactory
  Stream<DynamicTest> ownerAndLeaseContract() {
    var backend =
        new AsyncInboxStoreContract.Backend() {
          public AsyncInboxStore create() {
            var fake = new DynamoDbInboxStoreTest.FakeDynamo();
            return new DynamoDbAsyncInboxStore(
                new DynamoDbAsyncClient() {
                  public CompletableFuture<PutItemResponse> putItem(PutItemRequest r) {
                    return Stages.call(() -> CompletableFuture.completedFuture(fake.putItem(r)))
                        .toCompletableFuture();
                  }

                  public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest r) {
                    return Stages.call(() -> CompletableFuture.completedFuture(fake.updateItem(r)))
                        .toCompletableFuture();
                  }

                  public CompletableFuture<DeleteItemResponse> deleteItem(DeleteItemRequest r) {
                    return Stages.call(() -> CompletableFuture.completedFuture(fake.deleteItem(r)))
                        .toCompletableFuture();
                  }

                  public String serviceName() {
                    return "dynamodb";
                  }

                  public void close() {}
                },
                "inbox");
          }

          public boolean purgesOnDemand() {
            return false;
          }
        };
    return AsyncInboxStoreContract.checks(backend, Runnable::run).stream()
        .map(check -> DynamicTest.dynamicTest(check.name(), check::run));
  }

  @Test
  void cancellationReachesEachExactNativeFutureAndLeavesOtherOperationsAlone() {
    var raw = new Pending();
    var store = new DynamoDbAsyncInboxStore(raw, "inbox");
    Instant now = Instant.now();
    var acquire =
        store
            .tryAcquire("c", "m", "owner", now, now.plusSeconds(10), now.plusSeconds(100))
            .toCompletableFuture();
    var mark = store.markProcessed("c", "m", "owner", now).toCompletableFuture();
    var release = store.release("c", "m", "owner").toCompletableFuture();
    assertTrue(acquire.cancel(true));
    assertTrue(raw.put.isCancelled());
    assertFalse(raw.update.isDone());
    assertFalse(raw.delete.isDone());
    assertTrue(mark.cancel(true));
    assertTrue(raw.update.isCancelled());
    assertTrue(release.cancel(false));
    assertTrue(raw.delete.isCancelled());
    assertEquals(0, store.purgeExpired(now).toCompletableFuture().join());
    assertTrue(store.verifySchema().toCompletableFuture().isDone());
  }

  @Test
  void outagesNeverLookLikeBusyProcessedOrLostOwnership() {
    var raw = new Pending();
    var store = new DynamoDbAsyncInboxStore(raw, "inbox");
    Instant now = Instant.now();
    var acquire =
        store
            .tryAcquire("c", "m", "o", now, now.plusSeconds(10), now.plusSeconds(100))
            .toCompletableFuture();
    raw.put.completeExceptionally(SdkClientException.create("network"));
    assertInstanceOf(
        InboxException.class, assertThrows(CompletionException.class, acquire::join).getCause());
    var mark = store.markProcessed("c", "m", "o", now).toCompletableFuture();
    raw.update.completeExceptionally(SdkClientException.create("network"));
    assertInstanceOf(
        InboxException.class, assertThrows(CompletionException.class, mark::join).getCause());
    var release = store.release("c", "m", "o").toCompletableFuture();
    raw.delete.completeExceptionally(SdkClientException.create("network"));
    assertInstanceOf(
        InboxException.class, assertThrows(CompletionException.class, release::join).getCause());
  }

  private static final class Pending implements DynamoDbAsyncClient {
    final CompletableFuture<PutItemResponse> put = new CompletableFuture<>();
    final CompletableFuture<UpdateItemResponse> update = new CompletableFuture<>();
    final CompletableFuture<DeleteItemResponse> delete = new CompletableFuture<>();

    public CompletableFuture<PutItemResponse> putItem(PutItemRequest request) {
      return put;
    }

    public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest request) {
      return update;
    }

    public CompletableFuture<DeleteItemResponse> deleteItem(DeleteItemRequest request) {
      return delete;
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {}
  }
}
