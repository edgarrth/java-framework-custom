package pe.axiz.kit.dynamodb;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import pe.axiz.core.commons.Stages;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
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

/** One invocation owns its SDK cancellation binding; no thread-local or shared request state. */
final class NativeDynamoCall<T> extends CompletableFuture<T> implements DynamoDbAsyncClient {
  private final DynamoDbAsyncClient delegate;
  private final AtomicReference<CompletableFuture<?>> nativeRequest = new AtomicReference<>();
  private final AtomicReference<CompletableFuture<?>> mappedRequest = new AtomicReference<>();

  private NativeDynamoCall(DynamoDbAsyncClient delegate) {
    this.delegate = delegate;
  }

  static <T> CompletableFuture<T> submit(
      DynamoDbAsyncClient client,
      Function<DynamoDbEnhancedAsyncClient, CompletableFuture<T>> operation) {
    NativeDynamoCall<T> call = new NativeDynamoCall<>(client);
    try {
      var enhanced = DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(call).build();
      var mapped = operation.apply(enhanced);
      call.mappedRequest.set(mapped);
      if (call.isCancelled()) mapped.cancel(true);
      mapped.whenComplete(
          (value, failure) -> {
            if (failure == null) call.complete(value);
            else call.completeExceptionally(Stages.unwrap(failure));
          });
    } catch (RuntimeException | Error failure) {
      call.completeExceptionally(failure);
      var nativeFuture = call.nativeRequest.get();
      if (nativeFuture != null) nativeFuture.cancel(true);
    }
    return call;
  }

  private <R> CompletableFuture<R> track(CompletableFuture<R> future) {
    nativeRequest.set(future);
    if (isCancelled()) future.cancel(true);
    return future;
  }

  @Override
  public boolean cancel(boolean interrupt) {
    boolean cancelled = super.cancel(interrupt);
    if (cancelled) {
      var raw = nativeRequest.get();
      if (raw != null) raw.cancel(interrupt);
      var mapped = mappedRequest.get();
      if (mapped != null) mapped.cancel(interrupt);
    }
    return cancelled;
  }

  @Override
  public CompletableFuture<GetItemResponse> getItem(GetItemRequest request) {
    return track(delegate.getItem(request));
  }

  @Override
  public CompletableFuture<PutItemResponse> putItem(PutItemRequest request) {
    return track(delegate.putItem(request));
  }

  @Override
  public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest request) {
    return track(delegate.updateItem(request));
  }

  @Override
  public CompletableFuture<DeleteItemResponse> deleteItem(DeleteItemRequest request) {
    return track(delegate.deleteItem(request));
  }

  @Override
  public CompletableFuture<QueryResponse> query(QueryRequest request) {
    return track(delegate.query(request));
  }

  @Override
  public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
      TransactWriteItemsRequest request) {
    return track(delegate.transactWriteItems(request));
  }

  @Override
  public String serviceName() {
    return delegate.serviceName();
  }

  /** The application owns the borrowed transport. */
  @Override
  public void close() {}
}
