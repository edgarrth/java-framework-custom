package pe.axiz.spring.sequence.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.sequence.dynamodb.DynamoDbAsyncSequenceStore;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.sequence.AxizSequenceAutoConfiguration;
import pe.axiz.spring.sequence.ReactiveSequences;
import pe.axiz.spring.sequence.Sequences;
import reactor.blockhound.BlockHound;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

class ReactiveSequenceTest {
  @BeforeAll
  static void blockingDetection() {
    BlockHound.install();
  }

  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizSequenceAutoConfiguration.class, AxizSequenceDynamoDbAutoConfiguration.class));
  }

  @Test
  void facadeIsDeferredUsesNativePortOnNonBlockingThreadsAndAllocatesPerSubscription() {
    AtomicLong counter = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    reactive()
        .withBean(
            AsyncSequenceStore.class,
            () ->
                (name, period, amount) -> {
                  calls.incrementAndGet();
                  return CompletableFuture.completedFuture(counter.addAndGet(amount));
                })
        .withPropertyValues(
            "axiz.sequence.sequences.operation.prefix=OP-",
            "axiz.sequence.sequences.operation.width=2")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveSequences.class)
                  .doesNotHaveBean(Sequences.class)
                  .doesNotHaveBean(SequenceStore.class);
              var sequence = context.getBean(ReactiveSequences.class);
              var next = sequence.next("operation");
              assertThat(calls).hasValue(0);
              StepVerifier.create(next.subscribeOn(Schedulers.parallel()))
                  .expectNext("OP-01")
                  .verifyComplete();
              StepVerifier.create(next.subscribeOn(Schedulers.parallel()))
                  .expectNext("OP-02")
                  .verifyComplete();
              StepVerifier.create(sequence.next("missing"))
                  .expectError(IllegalArgumentException.class)
                  .verify();
              assertThat(calls).hasValue(2);
            });
  }

  @Test
  void cancellationReachesTheNativeReservationAndMissingNativeStoresFailAtStartup() {
    CompletableFuture<Long> nativeCall = new CompletableFuture<>();
    reactive()
        .withBean(AsyncSequenceStore.class, () -> (name, period, amount) -> nativeCall)
        .withPropertyValues("axiz.sequence.sequences.operation.prefix=OP-")
        .run(
            context -> {
              StepVerifier.create(context.getBean(ReactiveSequences.class).next("operation"))
                  .thenCancel()
                  .verify();
              assertThat(nativeCall).isCancelled();
            });
    reactive().run(context -> assertThat(context).hasFailed());
    reactive()
        .withPropertyValues("axiz.sequence.store=unknown")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void reusesAnExistingAsyncClientWithoutPublishingAnySynchronousClientOrFacade() {
    DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    reactive()
        .withPropertyValues("axiz.sequence.store=dynamodb")
        .withBean(DynamoDbAsyncClient.class, () -> client)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(ReactiveSequences.class)
                  .hasSingleBean(AsyncSequenceStore.class)
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .doesNotHaveBean(DynamoDbClient.class)
                  .doesNotHaveBean(SequenceStore.class)
                  .doesNotHaveBean(Sequences.class);
              assertThat(context.getBean(AsyncSequenceStore.class))
                  .isInstanceOf(DynamoDbAsyncSequenceStore.class);
              assertThat(context.getBean(DynamoDbAsyncClient.class)).isSameAs(client);
              verifyNoInteractions(client);
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void ownsAndWarmsItsAsyncClientAtStartupWhenNoClientWasProvided() {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues(
                        "axiz.sequence.store=dynamodb",
                        "axiz.aws.region=us-east-1",
                        "axiz.aws.endpoint=https://localhost:4566")
                    .run(
                        context -> {
                          assertThat(context)
                              .hasNotFailed()
                              .hasSingleBean(AsyncAwsClients.class)
                              .hasSingleBean(DynamoDbAsyncClient.class)
                              .hasSingleBean(AsyncSequenceStore.class)
                              .doesNotHaveBean(DynamoDbClient.class);
                          assertThat(
                                  context
                                      .getBean(DynamoDbAsyncClient.class)
                                      .serviceClientConfiguration()
                                      .endpointOverride())
                              .hasValue(URI.create("https://localhost:4566"));
                        }));
  }

  @Test
  void invalidConfigurationFailsBeforeServingTraffic() {
    reactive()
        .withBean(
            AsyncSequenceStore.class,
            () -> (name, period, amount) -> CompletableFuture.completedFuture(1L))
        .withPropertyValues("axiz.sequence.sequences.operation.block-size=0")
        .run(context -> assertThat(context).hasFailed());
  }
}
