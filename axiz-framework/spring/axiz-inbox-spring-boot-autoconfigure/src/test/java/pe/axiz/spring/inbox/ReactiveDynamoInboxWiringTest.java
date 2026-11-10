package pe.axiz.spring.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.inbox.dynamodb.DynamoDbAsyncInboxStore;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.core.inbox.AsyncInboxStore;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

class ReactiveDynamoInboxWiringTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizInboxDynamoDbAutoConfiguration.class, AxizInboxAutoConfiguration.class))
        .withPropertyValues("axiz.inbox.store=dynamodb")
        .withBean(
            DynamoDbClient.class,
            () ->
                new DynamoDbClient() {
                  public String serviceName() {
                    return "dynamodb";
                  }

                  public void close() {}
                });
  }

  @Test
  void reusesAsyncClientAndCancellationReachesItsRequest() {
    var raw = new Pending();
    reactive()
        .withBean(DynamoDbAsyncClient.class, () -> raw)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(AsyncInboxStore.class)
                  .hasSingleBean(ReactiveInbox.class)
                  .doesNotHaveBean(AsyncAwsClients.class);
              var now = Instant.now();
              StepVerifier.create(
                      ctx.getBean(ReactiveInbox.class)
                          .tryAcquire(
                              "c", "m", "owner", now, now.plusSeconds(10), now.plusSeconds(100)))
                  .thenCancel()
                  .verify();
              assertTrue(raw.put.isCancelled());
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void configuresOwnedResourcesAtStartup() {
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
                              .hasSingleBean(AsyncInboxStore.class)
                              .hasSingleBean(ReactiveInbox.class);
                          assertThat(
                                  ctx.getBean(DynamoDbAsyncClient.class)
                                      .serviceClientConfiguration()
                                      .endpointOverride())
                              .hasValue(URI.create("http://localhost:4566"));
                        }));
  }

  @Test
  void customAsyncPortBacksOffCredentialAndClientResources() {
    AsyncInboxStore own = new DynamoDbAsyncInboxStore(new Pending(), "own-inbox");
    reactive()
        .withBean(AsyncInboxStore.class, () -> own)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean(DynamoDbAsyncClient.class)
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .hasSingleBean(ReactiveInbox.class);
              assertThat(ctx.getBean(AsyncInboxStore.class)).isSameAs(own);
            });
  }

  private static final class Pending implements DynamoDbAsyncClient {
    final CompletableFuture<PutItemResponse> put = new CompletableFuture<>();

    public CompletableFuture<PutItemResponse> putItem(PutItemRequest request) {
      return put;
    }

    public String serviceName() {
      return "dynamodb";
    }

    public void close() {}
  }
}
