package pe.axiz.spring.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.AuditChainVerifier;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditSealer;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.audit.ChainCheckpoint;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@ExtendWith(BlockHoundExtension.class)
class AuditMemoryCheckpointsTest {
  private AuditIntegritySupport integrity() {
    return new AuditIntegritySupport(
        new AxizAuditProperties.Integrity(AuditSealer.Mode.HASH, null, null),
        new MockEnvironment().withProperty("axiz.config.environment", "test"),
        new DefaultListableBeanFactory(),
        Clock.systemUTC());
  }

  private AxizAuditProperties.Strict properties(Duration timeout) {
    return new AxizAuditProperties.Strict(
        false, AxizAuditProperties.StrictMode.DIRECT, timeout, 1, Duration.ofHours(1));
  }

  private AuditEvent event() {
    return DurableAuditTest.event(AuditEvent.Outcome.SUCCESS);
  }

  @Test
  void onlyAnAcknowledgedSnapshotBecomesEvidenceAndADelayedEventRemainsMissing() {
    var registry = new SimpleMeterRegistry();
    try (var integrity = integrity()) {
      var sealer = integrity.memory("async");
      var business = sealer.seal(event());
      List<AuditEvent> received = new CopyOnWriteArrayList<>();
      var acknowledgement = new AtomicReference<>(new CompletableFuture<Void>());
      try (var checkpoints =
          new AuditMemoryCheckpoints(
              integrity,
              properties(Duration.ofSeconds(5)),
              checkpoint -> {
                received.add(checkpoint);
                return acknowledgement.get();
              },
              registry)) {
        checkpoints.start();
        checkpoints.tick();
        await().atMost(Duration.ofSeconds(3)).until(() -> received.size() == 1);
        assertThat(checkpoints.confirmedSnapshots()).isEmpty();
        for (int i = 0; i < 20; i++) checkpoints.tick();
        assertEquals(1, received.size());
        acknowledgement.get().complete(null);
        await().until(() -> checkpoints.confirmedSnapshots().size() == 1);
        assertEquals(1, registry.get("axiz.audit.checkpoint.confirmed").functionCounter().count());
        var checkpoint = fromDurableEvent(received.getFirst());
        assertEquals("audit.chain.checkpoint", received.getFirst().action());
        assertEquals(
            AuditChainVerifier.Completeness.TAIL_MISSING,
            AuditChainVerifier.verify(List.of(), null, checkpoint).completeness());
        assertEquals(
            AuditChainVerifier.Completeness.COMPLETE_UP_TO,
            AuditChainVerifier.verify(List.of(business), null, checkpoint).completeness());
        assertThrows(
            UnsupportedOperationException.class, () -> checkpoints.confirmedSnapshots().clear());
        checkpoints.tick();
        assertEquals(1, received.size());

        sealer.seal(event());
        acknowledgement.set(new CompletableFuture<>());
        checkpoints.tick();
        await().until(() -> received.size() == 2);
        acknowledgement.get().completeExceptionally(new IllegalStateException("sensitive-canary"));
        await()
            .until(
                () -> registry.get("axiz.audit.checkpoint.failed").functionCounter().count() == 1);
        assertEquals(checkpoint, checkpoints.confirmedSnapshots().get(checkpoint.chainId()));
        acknowledgement.set(CompletableFuture.completedFuture(null));
        checkpoints.tick();
        await()
            .until(
                () -> checkpoints.confirmedSnapshots().get(checkpoint.chainId()).sequence() == 2);
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void timeoutRetainsBoundedAdmissionAndLateSuccessCannotConfirmAnExpiredSnapshot() {
    var registry = new SimpleMeterRegistry();
    try (var integrity = integrity()) {
      integrity.memory("async").seal(event());
      var accepted = new AtomicInteger();
      var pending = new CompletableFuture<Void>();
      try (var checkpoints =
          new AuditMemoryCheckpoints(
              integrity,
              properties(Duration.ofMillis(100)),
              checkpoint -> {
                accepted.incrementAndGet();
                return pending;
              },
              registry)) {
        checkpoints.start();
        checkpoints.tick();
        await()
            .until(
                () -> registry.get("axiz.audit.checkpoint.failed").functionCounter().count() == 1);
        checkpoints.tick();
        await()
            .until(
                () -> registry.get("axiz.audit.checkpoint.failed").functionCounter().count() == 2);
        for (int i = 0; i < 30; i++) checkpoints.tick();
        assertEquals(2, accepted.get());
        pending.complete(null);
        assertThat(checkpoints.confirmedSnapshots()).isEmpty();
      }
    } finally {
      registry.close();
    }
  }

  @Test
  void stoppingPreventsLateAcknowledgementsAndRestartCanDeliverAFreshSnapshot() {
    try (var integrity = integrity()) {
      integrity.memory("sync").seal(event());
      var pending = new AtomicReference<>(new CompletableFuture<Void>());
      var accepted = new AtomicInteger();
      try (var checkpoints =
          new AuditMemoryCheckpoints(
              integrity,
              properties(Duration.ofSeconds(5)),
              checkpoint -> {
                accepted.incrementAndGet();
                return pending.get();
              },
              null)) {
        checkpoints.start();
        checkpoints.tick();
        await().until(() -> accepted.get() == 1);
        checkpoints.stop();
        pending.get().complete(null);
        assertFalse(checkpoints.isRunning());
        assertThat(checkpoints.confirmedSnapshots()).isEmpty();
        checkpoints.tick();
        assertEquals(1, accepted.get());
        pending.set(CompletableFuture.completedFuture(null));
        checkpoints.start();
        checkpoints.tick();
        await().until(() -> checkpoints.confirmedSnapshots().size() == 1);
      }
    }
  }

  @Test
  void oneCycleCoversAllMemoryLanesAndTheStrictChainCheckpointCoversOnlyItsPreviousHead() {
    try (var integrity = integrity()) {
      integrity.memory("sync").seal(event());
      integrity.memory("degradation").seal(event());
      var strict = integrity.memory("strict");
      List<AuditEvent> received = new CopyOnWriteArrayList<>();
      try (var checkpoints =
          new AuditMemoryCheckpoints(
              integrity,
              properties(Duration.ofSeconds(3)),
              checkpoint -> {
                received.add(strict.seal(checkpoint));
                return CompletableFuture.completedFuture(null);
              },
              null)) {
        checkpoints.start();
        checkpoints.tick();
        await().until(() -> checkpoints.confirmedSnapshots().size() == 2);
        checkpoints.tick();
        await().until(() -> checkpoints.confirmedSnapshots().size() == 3);
        assertEquals(3, received.size());
        var checkpoint = fromDurableEvent(received.getLast());
        assertEquals(2, checkpoint.sequence());
        assertEquals(3, received.getLast().integrity().sequence());
        assertEquals(
            AuditChainVerifier.Completeness.COMPLETE_UP_TO,
            AuditChainVerifier.verify(received, null, checkpoint).completeness());
      }
    }
  }

  private AutoConfigurations configurations() {
    return AutoConfigurations.of(
        AxizAuditAutoConfiguration.class,
        AxizNativeAuditAutoConfiguration.class,
        AxizStrictAuditAutoConfiguration.class,
        AxizAuditCheckpointAutoConfiguration.class);
  }

  @Test
  void springLifecyclePublishesPeriodicallyWithoutAnExplicitTick() {
    List<AuditEvent> received = new CopyOnWriteArrayList<>();
    new ApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(configurations())
        .withPropertyValues("axiz.audit.mode=ASYNC", "axiz.audit.strict.checkpoint-interval=1s")
        .withBean(StrictAuditPublisher.class, () -> received::add)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              ctx.getBean(AuditTrail.class).record("modify", "customer").success();
              await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 1);
              assertEquals("audit.chain.checkpoint", received.getFirst().action());
            });
  }

  @Test
  void aCustomStrictPortWorksWithoutEnablingTheAxizStrictImplementation() {
    List<AuditEvent> received = new CopyOnWriteArrayList<>();
    var runner =
        new ApplicationContextRunner()
            .withPropertyValues("axiz.config.environment=test")
            .withConfiguration(configurations())
            .withPropertyValues("axiz.audit.mode=ASYNC");
    runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(AuditMemoryCheckpoints.class));
    runner
        .withBean(StrictAuditPublisher.class, () -> received::add)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(AuditMemoryCheckpoints.class);
              ctx.getBean(AuditTrail.class).record("modify", "customer").success();
              var checkpoints = ctx.getBean(AuditMemoryCheckpoints.class);
              assertTrue(checkpoints.isRunning());
              checkpoints.tick();
              await().until(() -> checkpoints.confirmedSnapshots().size() == 1);
              assertEquals(1, received.size());
            });
    runner
        .withPropertyValues("axiz.config.environment=test", "axiz.audit.integrity.mode=NONE")
        .withBean(StrictAuditPublisher.class, () -> received::add)
        .run(ctx -> assertFalse(ctx.getBean(AuditMemoryCheckpoints.class).isRunning()));
    runner
        .withPropertyValues("axiz.audit.enabled=false")
        .withBean(StrictAuditPublisher.class, () -> received::add)
        .run(ctx -> assertThat(ctx).doesNotHaveBean(AuditMemoryCheckpoints.class));
  }

  @Test
  void nativeCheckpointPublicationDoesNotWaitForTheAcknowledgementOnTheEventLoop() {
    var confirmation = new CompletableFuture<Void>();
    var received = new AtomicInteger();
    new ReactiveWebApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(configurations())
        .withPropertyValues("axiz.audit.mode=ASYNC")
        .withBean(
            AsyncStrictAuditPublisher.class,
            () ->
                event -> {
                  received.incrementAndGet();
                  return confirmation;
                })
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              ctx.getBean(AuditIntegritySupport.class).memory("async").seal(event());
              var checkpoints = ctx.getBean(AuditMemoryCheckpoints.class);
              StepVerifier.create(
                      Mono.fromRunnable(checkpoints::tick).subscribeOn(Schedulers.parallel()))
                  .verifyComplete();
              await().until(() -> received.get() == 1);
              assertThat(checkpoints.confirmedSnapshots()).isEmpty();
              confirmation.complete(null);
              await().until(() -> checkpoints.confirmedSnapshots().size() == 1);
            });
  }

  private ChainCheckpoint fromDurableEvent(AuditEvent event) {
    Map<String, ?> details = event.details();
    return new ChainCheckpoint(
        (String) details.get("chainId"),
        ((Number) details.get("sequence")).longValue(),
        (String) details.get("hash"),
        Instant.parse((String) details.get("at")));
  }
}
