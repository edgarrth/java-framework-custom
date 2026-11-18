package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class StrictAuditWiringTest {
  private ApplicationContextRunner imperative() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class, AxizStrictAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.mode=SYNC", "axiz.audit.strict.enabled=true");
  }

  private ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class,
                AxizStrictAuditAutoConfiguration.class))
        .withPropertyValues("axiz.audit.mode=ASYNC", "axiz.audit.strict.enabled=true");
  }

  @Test
  void genericDestinationsCannotSilentlyQualifyAsDurableAndDisabledCreatesNoStrictPort() {
    imperative()
        .withBean(AuditSink.class, () -> new AuditSink("generic", event -> {}))
        .run(ctx -> assertThat(ctx).hasFailed());
    reactive()
        .withBean(
            AsyncAuditSink.class,
            () -> new AsyncAuditSink("generic", event -> CompletableFuture.completedFuture(null)))
        .run(ctx -> assertThat(ctx).hasFailed());
    imperative()
        .withPropertyValues("axiz.audit.strict.enabled=false")
        .run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(StrictAuditPublisher.class));
    imperative()
        .withPropertyValues("axiz.config.environment=test", "axiz.audit.integrity.mode=NONE")
        .withBean(StrictAuditSink.class, () -> new StrictAuditSink("durable", event -> {}))
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void directConfirmationWorksWithoutTransactionOrOutboxClassesAndPreservesItsSeal() {
    List<AuditEvent> stored = new CopyOnWriteArrayList<>();
    imperative()
        .withClassLoader(
            new FilteredClassLoader(
                "org.springframework.transaction",
                "pe.axiz.core.outbox",
                "pe.axiz.kit.reactor.outbox"))
        .withBean(StrictAuditSink.class, () -> new StrictAuditSink("durable", stored::add))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(StrictAuditPublisher.class);
              ctx.getBean(StrictAuditPublisher.class)
                  .publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS));
              assertEquals(1, stored.size());
              assertEquals(
                  AuditChainVerifier.IntegrityStatus.INTACT,
                  AuditChainVerifier.verify(stored, null, null).integrity());
            });
  }

  @Test
  void destinationFailurePropagatesInsteadOfAcceptingLogFallback() {
    imperative()
        .withBean(
            StrictAuditSink.class,
            () ->
                new StrictAuditSink(
                    "unavailable",
                    event -> {
                      throw new IllegalStateException("not acknowledged");
                    }))
        .run(
            ctx ->
                assertThrows(
                    AuditPublishException.class,
                    () ->
                        ctx.getBean(StrictAuditPublisher.class)
                            .publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS))));
  }

  @Test
  void timeoutDoesNotReleaseCapacityForAnUnfinishedWriteOrReportSuccess() {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    imperative()
        .withPropertyValues("axiz.audit.strict.timeout=100ms", "axiz.audit.strict.concurrency=1")
        .withBean(
            StrictAuditSink.class,
            () ->
                new StrictAuditSink(
                    "blocked",
                    event -> {
                      entered.countDown();
                      try {
                        release.await(3, TimeUnit.SECONDS);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                      }
                    }))
        .run(
            ctx -> {
              try {
                var publisher = ctx.getBean(StrictAuditPublisher.class);
                long start = System.nanoTime();
                assertThrows(
                    AuditPublishException.class,
                    () -> publisher.publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS)));
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
                assertThrows(
                    AuditPublishException.class,
                    () -> publisher.publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS)));
              } finally {
                release.countDown();
              }
            });
  }

  @Test
  void nativeFailureIsNotConvertedToQueueAcceptanceAndSuccessCarriesASeal() {
    var stored = new CompletableFuture<AuditEvent>();
    reactive()
        .withBean(
            AsyncStrictAuditSink.class,
            () ->
                new AsyncStrictAuditSink(
                    "native",
                    event -> {
                      stored.complete(event);
                      return CompletableFuture.failedFuture(
                          new IllegalStateException("not acknowledged"));
                    }))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(AsyncStrictAuditPublisher.class)
                  .doesNotHaveBean(StrictAuditPublisher.class);
              StepVerifier.create(
                      Mono.fromCompletionStage(
                              () ->
                                  ctx.getBean(AsyncStrictAuditPublisher.class)
                                      .publish(DurableAuditTest.event(AuditEvent.Outcome.SUCCESS)))
                          .subscribeOn(Schedulers.parallel()))
                  .expectErrorMessage("not acknowledged")
                  .verify(Duration.ofSeconds(3));
              assertThat(stored.join().integrity()).isNotNull();
            });
    reactive()
        .withBean(
            AsyncStrictAuditSink.class,
            () ->
                new AsyncStrictAuditSink(
                    "native", event -> CompletableFuture.completedFuture(null)))
        .run(
            ctx ->
                StepVerifier.create(
                        Mono.fromCompletionStage(
                                () ->
                                    ctx.getBean(AsyncStrictAuditPublisher.class)
                                        .publish(
                                            DurableAuditTest.event(AuditEvent.Outcome.SUCCESS)))
                            .subscribeOn(Schedulers.parallel()))
                    .expectComplete()
                    .verify(Duration.ofSeconds(3)));
  }
}
