package pe.axiz.spring.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfigurationTest.Item;
import pe.axiz.spring.dynamodb.AxizDynamoDbAutoConfigurationTest.Movement;
import reactor.test.StepVerifier;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
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

class ReactiveDynamoRepositoryTest {
  @Test
  void facadesSubmitColdWritesAndPagesAndPreserveSubmissionErrors() {
    var raw = new CrudClient();
    var repository =
        new ReactiveDynamoRepositories(
                raw,
                "",
                () ->
                    new pe.axiz.kit.dynamodb.CursorCodec(
                        new pe.axiz.core.crypto.StaticKeyProvider(
                            "test", java.util.Map.of("test", pe.axiz.core.crypto.AesGcm.newKey())),
                        false))
            .create("items", Item.class);
    var item = new Item();
    item.setId("C-1");
    var work =
        repository
            .save(item)
            .then(repository.insert(item, "id"))
            .then(repository.update(item))
            .doOnNext(updated -> assertEquals("C-1", updated.getId()))
            .then(repository.delete("C-1"))
            .then(
                repository.transact(
                    b ->
                        b.addPutItem(
                            repository.table(),
                            TransactPutItemEnhancedRequest.builder(Item.class).item(item).build())))
            .then(repository.query("C-1", 2, null));
    assertEquals(0, raw.calls.get());
    StepVerifier.create(work)
        .assertNext(
            page -> {
              assertEquals(1, page.content().size());
              assertEquals("C-1", page.content().getFirst().getId());
            })
        .verifyComplete();
    assertEquals(6, raw.calls.get());
    // Invalid keys/indexes and cursor limits must fail only upon subscription and submit no I/O.
    StepVerifier.create(repository.findById("C-1", "S-1"))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(repository.delete("C-1", "S-1"))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(repository.queryBeginsWith("C-1", "S-", 2, null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(repository.queryIndex("missing", "C-1", 2, null))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(repository.query("C-1", 0, null))
        .expectError(IllegalArgumentException.class)
        .verify();
    assertEquals(6, raw.calls.get());
  }

  private static final class CrudClient implements DynamoDbAsyncClient {
    final AtomicInteger calls = new AtomicInteger();

    private <R> CompletableFuture<R> response(R value) {
      calls.incrementAndGet();
      return CompletableFuture.completedFuture(value);
    }

    public CompletableFuture<PutItemResponse> putItem(PutItemRequest request) {
      return response(PutItemResponse.builder().build());
    }

    public CompletableFuture<UpdateItemResponse> updateItem(UpdateItemRequest request) {
      return response(
          UpdateItemResponse.builder()
              .attributes(Map.of("id", AttributeValue.fromS("C-1")))
              .build());
    }

    public CompletableFuture<DeleteItemResponse> deleteItem(DeleteItemRequest request) {
      return response(DeleteItemResponse.builder().build());
    }

    public CompletableFuture<QueryResponse> query(QueryRequest request) {
      return response(
          QueryResponse.builder()
              .items(java.util.List.of(Map.of("id", AttributeValue.fromS("C-1"))))
              .build());
    }

    public CompletableFuture<TransactWriteItemsResponse> transactWriteItems(
        TransactWriteItemsRequest request) {
      return response(TransactWriteItemsResponse.builder().build());
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {}
  }

  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizDynamoDbAutoConfiguration.class));
  }

  @Test
  void borrowedClientCreatesOnlyNativeRepositoriesAndDefersIo() {
    var raw = new Scripted();
    reactive()
        .withPropertyValues("axiz.dynamodb.table-prefix=payments-")
        .withBean(DynamoDbAsyncClient.class, () -> raw, bean -> bean.setDestroyMethodName(""))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveDynamoRepositories.class)
                  .doesNotHaveBean(DynamoDbClient.class)
                  .doesNotHaveBean(DynamoRepositories.class)
                  .doesNotHaveBean(AsyncAwsClients.class);
              var factory = ctx.getBean(ReactiveDynamoRepositories.class);
              assertEquals("payments-items", factory.tableName("items"));
              assertThat(factory.create("movements", Movement.class)).isNotNull();
              var repository = factory.create("items", Item.class);
              var missing = repository.findById("C-1");
              assertEquals(0, raw.calls.get());
              StepVerifier.create(missing)
                  .then(
                      () -> {
                        assertEquals(1, raw.calls.get());
                        raw.pending.complete(GetItemResponse.builder().build());
                      })
                  .verifyComplete();
              StepVerifier.create(missing).thenCancel().verify();
              assertTrue(raw.pending.isCancelled());
              assertEquals(2, raw.calls.get(), "each subscription submits its own operation");
            });
    assertFalse(raw.closed, "only a client created by the framework belongs to the context");
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void ownsItsConfiguredNativeClientWithoutCreatingAnImperativeClient() {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues(
                        "axiz.aws.region=us-east-1",
                        "axiz.aws.dynamodb.endpoint=http://localhost:4566")
                    .run(
                        ctx -> {
                          assertThat(ctx)
                              .hasNotFailed()
                              .hasSingleBean(AsyncAwsClients.class)
                              .hasSingleBean(DynamoDbAsyncClient.class)
                              .hasSingleBean(ReactiveDynamoRepositories.class)
                              .doesNotHaveBean(DynamoDbClient.class)
                              .doesNotHaveBean(DynamoRepositories.class);
                          assertThat(
                                  ctx.getBean(DynamoDbAsyncClient.class)
                                      .serviceClientConfiguration()
                                      .endpointOverride())
                              .hasValue(java.net.URI.create("http://localhost:4566"));
                        }));
  }

  @Test
  void customFactoryAvoidsCreatingCredentialsAndClients() {
    var factory = new ReactiveDynamoRepositories(new Scripted(), "own-");
    reactive()
        .withBean(ReactiveDynamoRepositories.class, () -> factory)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean(DynamoDbAsyncClient.class)
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .doesNotHaveBean(DynamoDbClient.class);
              assertThat(ctx.getBean(ReactiveDynamoRepositories.class)).isSameAs(factory);
            });
  }

  private static final class Scripted implements DynamoDbAsyncClient {
    final AtomicInteger calls = new AtomicInteger();
    CompletableFuture<GetItemResponse> pending = new CompletableFuture<>();
    boolean closed;

    public CompletableFuture<GetItemResponse> getItem(GetItemRequest request) {
      assertEquals("payments-items", request.tableName());
      calls.incrementAndGet();
      pending = new CompletableFuture<>();
      return pending;
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {
      closed = true;
    }
  }
}
