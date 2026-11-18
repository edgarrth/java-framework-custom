package pe.axiz.spring.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.NullObservation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import pe.axiz.core.concurrent.AsyncLoadingCache;
import pe.axiz.core.concurrent.Stages;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ReactiveStagesTest {
  private final ObservationRegistry registry = ObservationRegistry.create();

  ReactiveStagesTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    ReactiveInfrastructureBootstrap.initialize();
  }

  private Context context(Observation observation) {
    return Context.of(ObservationThreadLocalAccessor.KEY, observation);
  }

  private void assertNoActiveObservation() {
    var observation = registry.getCurrentObservation();
    // Micrometer clears an existing scope with a NullObservation, then restores it at close.
    assertTrue(
        observation == null || observation instanceof NullObservation || observation.isNoop());
  }

  @Test
  void coalescedSubscribersKeepTheirOwnParentAndRestoreTheCompletionThread() throws Exception {
    var cache = new AsyncLoadingCache<String>(Duration.ofMinutes(1), 100, 2, Clock.systemUTC());
    var nativeCall = new CompletableFuture<String>();
    var first = Observation.start("first", registry);
    var second = Observation.start("second", registry);
    var worker = Observation.start("worker", registry);
    CompletableFuture<String> a =
        ReactiveStages.from(
                () -> {
                  assertSame(first, registry.getCurrentObservation());
                  return cache.get("key", () -> nativeCall);
                })
            .doOnNext(value -> assertSame(first, registry.getCurrentObservation()))
            .contextWrite(context(first))
            .toFuture();
    CompletableFuture<String> b =
        ReactiveStages.from(
                () -> {
                  assertSame(second, registry.getCurrentObservation());
                  return cache.get(
                      "key",
                      () -> {
                        throw new AssertionError("Duplicate I/O");
                      });
                })
            .doOnNext(value -> assertSame(second, registry.getCurrentObservation()))
            .contextWrite(context(second))
            .toFuture();
    assertNull(registry.getCurrentObservation());
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                try (var scope = worker.openScope()) {
                  nativeCall.complete("answer");
                  assertSame(worker, registry.getCurrentObservation());
                }
                assertNull(registry.getCurrentObservation());
              })
          .get(5, TimeUnit.SECONDS);
    }
    assertEquals("answer", a.get(5, TimeUnit.SECONDS));
    assertEquals("answer", b.get(5, TimeUnit.SECONDS));
    first.stop();
    second.stop();
    worker.stop();
  }

  @Test
  void immediateResultsAndCacheHitsScopeSignalsAsWellAsSubmission() {
    var parent = Observation.start("request", registry);
    var unrelated = Observation.start("caller", registry);
    try (var scope = unrelated.openScope()) {
      StepVerifier.create(
              ReactiveStages.from(
                      () -> {
                        assertSame(parent, registry.getCurrentObservation());
                        return CompletableFuture.completedFuture("cached");
                      })
                  .doOnNext(value -> assertSame(parent, registry.getCurrentObservation()))
                  .doOnSuccess(value -> assertSame(parent, registry.getCurrentObservation()))
                  .contextWrite(context(parent)))
          .expectNext("cached")
          .verifyComplete();
      assertSame(unrelated, registry.getCurrentObservation());
    }
    assertNull(registry.getCurrentObservation());
    parent.stop();
    unrelated.stop();
  }

  @Test
  void composedFallbackSubmissionKeepsTheParentAfterNativeCompletion() throws Exception {
    var parent = Observation.start("request", registry);
    var firstNativeCall = new CompletableFuture<String>();
    var result =
        ReactiveStages.from(
                () ->
                    Stages.compose(
                        firstNativeCall,
                        value -> {
                          assertSame(parent, registry.getCurrentObservation());
                          return Stages.map(
                              CompletableFuture.completedFuture("fallback"),
                              answer -> {
                                assertSame(parent, registry.getCurrentObservation());
                                return answer;
                              });
                        }))
            .contextWrite(context(parent))
            .toFuture();
    try (var executor = Executors.newSingleThreadExecutor()) {
      executor
          .submit(
              () -> {
                firstNativeCall.complete("missing");
                assertNull(registry.getCurrentObservation());
              })
          .get(5, TimeUnit.SECONDS);
    }
    assertEquals("fallback", result.get(5, TimeUnit.SECONDS));
    parent.stop();
  }

  @Test
  void cancellationAndSubmissionErrorsRestoreTheCallingThread() {
    var parent = Observation.start("request", registry);
    var nativeCall =
        new CompletableFuture<String>() {
          @Override
          public boolean cancel(boolean interrupt) {
            assertSame(parent, registry.getCurrentObservation());
            return super.cancel(interrupt);
          }
        };
    StepVerifier.create(ReactiveStages.from(() -> nativeCall).contextWrite(context(parent)))
        .thenCancel()
        .verify();
    assertTrue(nativeCall.isCancelled());
    assertNull(registry.getCurrentObservation());
    var failure = new IllegalStateException("submit");
    StepVerifier.create(
            ReactiveStages.<String>from(
                    () -> {
                      throw failure;
                    })
                .doOnError(error -> assertSame(parent, registry.getCurrentObservation()))
                .contextWrite(context(parent)))
        .expectErrorMatches(error -> error == failure)
        .verify();
    assertNull(registry.getCurrentObservation());
    parent.stop();
  }

  @Test
  void missingRequestContextClearsAndRestoresAnUnrelatedObservation() {
    var unrelated = Observation.start("caller", registry);
    try (var scope = unrelated.openScope()) {
      StepVerifier.create(
              ReactiveStages.from(
                      () -> {
                        assertNoActiveObservation();
                        return CompletableFuture.completedFuture("clean");
                      })
                  .doOnNext(value -> assertNoActiveObservation()))
          .expectNext("clean")
          .verifyComplete();
      assertSame(unrelated, registry.getCurrentObservation());
    }
    unrelated.stop();
  }

  @Test
  void streamingDemandAndCancellationAreScopedWithoutPrefetch() {
    var parent = Observation.start("stream", registry);
    var requested = new AtomicLong();
    var cancelled = new AtomicBoolean();
    StepVerifier.create(
            ReactiveStages.<String>flux(
                    () -> {
                      assertSame(parent, registry.getCurrentObservation());
                      return subscriber ->
                          subscriber.onSubscribe(
                              new Subscription() {
                                public void request(long count) {
                                  assertSame(parent, registry.getCurrentObservation());
                                  requested.addAndGet(count);
                                  subscriber.onNext("chunk");
                                }

                                public void cancel() {
                                  assertSame(parent, registry.getCurrentObservation());
                                  cancelled.set(true);
                                }
                              });
                    })
                .doOnNext(value -> assertSame(parent, registry.getCurrentObservation()))
                .contextWrite(context(parent)),
            0)
        .then(() -> assertEquals(0, requested.get()))
        .thenRequest(1)
        .expectNext("chunk")
        .thenCancel()
        .verify();
    assertEquals(1, requested.get());
    assertTrue(cancelled.get());
    assertNull(registry.getCurrentObservation());
    parent.stop();
  }
}
