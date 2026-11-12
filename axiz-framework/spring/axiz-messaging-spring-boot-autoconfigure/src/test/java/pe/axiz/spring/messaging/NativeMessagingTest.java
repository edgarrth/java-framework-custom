package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationHandler.FirstMatchingCompositeObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.messaging.RoutingAsyncEventPublisher;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(BlockHoundExtension.class)
class NativeMessagingTest {
  private static final Duration T = Duration.ofSeconds(5);

  @BeforeAll
  static void warm() {
    ReactiveInfrastructureBootstrap.initialize();
  }

  @AfterEach
  void noBlocking() {
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }

  private static AxizMessagingProperties properties() {
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of("axiz.messaging.destinations.orders", "memory:orders")))
        .bindOrCreate("axiz.messaging", AxizMessagingProperties.class);
  }

  private static EventEnvelope event() {
    return new EventEnvelope(
        "id",
        "urn:service",
        "event.v1",
        Instant.EPOCH,
        "subject",
        "application/json",
        new byte[0],
        Map.of());
  }

  @Test
  void metadataAndObservationsRemainPerSubscriberAcrossNativeCompletionThreads() {
    List<EventEnvelope> sent = new CopyOnWriteArrayList<>();
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    try (var worker = Executors.newSingleThreadExecutor()) {
      var publisher =
          new ObservedAsyncEventPublisher(
              (destination, envelope) -> {
                assertEquals("memory:orders", destination);
                sent.add(envelope);
                var nativeCall = new CompletableFuture<PublishResult>();
                worker.submit(
                    () ->
                        nativeCall.complete(
                            new PublishResult(envelope.id(), destination, "accepted")));
                return nativeCall;
              },
              registry,
              properties());
      AtomicInteger ids = new AtomicInteger();
      var factory =
          new EventFactory(
              "urn:service",
              "pe.axiz",
              new JacksonPayloadCodec(JsonMapper.builder().build()),
              () -> "id-" + ids.incrementAndGet(),
              Clock.systemUTC());
      var events = new ReactiveEvents(factory, publisher);
      var work = events.publish("orders", "created.v1", "account", Map.of("amount", "10.00"));
      assertTrue(sent.isEmpty());
      for (String correlation : List.of("A", "B")) {
        var parent = Observation.start("caller", registry);
        StepVerifier.create(
                work.doOnNext(ignored -> assertSame(parent, registry.getCurrentObservation()))
                    .contextWrite(
                        c ->
                            c.put(
                                    RequestContext.KEY,
                                    RequestMetadata.builder(correlation, "req")
                                        .tenantId("tenant")
                                        .build())
                                .put(ObservationThreadLocalAccessor.KEY, parent))
                    .subscribeOn(Schedulers.parallel()))
            .expectNextMatches(result -> "accepted".equals(result.brokerMessageId()))
            .expectComplete()
            .verify(T);
        parent.stop();
      }
      assertEquals(
          List.of("A", "B"), sent.stream().map(e -> e.correlationId().orElseThrow()).toList());
      assertTrue(
          sent.stream()
              .allMatch(e -> e.extension(EventEnvelope.TENANT).orElseThrow().equals("tenant")));
      assertThat(
              stopped.stream()
                  .filter(c -> AxizObservations.EVENT_PUBLISH.equals(c.getName()))
                  .map(Observation.Context::getParentObservation))
          .doesNotContainNull()
          .hasSize(2);
      assertTrue(publisher.supports("orders"));
      assertNull(registry.getCurrentObservation());
    }
  }

  @Test
  void cancellationAndSubmissionFailureStopObservationsAndCancelTheExactNativeFuture()
      throws Exception {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    CountDownLatch cancelled = new CountDownLatch(1);
    CountDownLatch submitted = new CountDownLatch(1);
    CompletableFuture<PublishResult> pending =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean result = super.cancel(interrupt);
            cancelled.countDown();
            return result;
          }
        };
    var publisher =
        new ObservedAsyncEventPublisher(
            (destination, event) -> {
              submitted.countDown();
              return pending;
            },
            registry,
            properties());
    StepVerifier.create(
            new ReactiveEvents(factory(), publisher)
                .publish("orders", event())
                .subscribeOn(Schedulers.parallel()))
        .then(() -> await(submitted))
        .thenCancel()
        .verify(T);
    assertTrue(cancelled.await(5, TimeUnit.SECONDS));
    assertTrue(pending.isCancelled());
    assertEquals(1, stopped.size());
    assertThat(stopped.getFirst().getError()).isInstanceOf(CancellationException.class);
    IllegalStateException original = new IllegalStateException("rejected");
    var rejected =
        new ObservedAsyncEventPublisher(
            (destination, event) -> {
              throw original;
            },
            registry,
            properties());
    assertSame(
        original,
        assertThrows(
                CompletionException.class,
                () -> rejected.publish("orders", event()).toCompletableFuture().join())
            .getCause());
    assertEquals(2, stopped.size());
    assertNull(registry.getCurrentObservation());
  }

  @Test
  void nativeOutboxContinuesTheStoredTraceAndStripsPrivateExtensions() {
    var tracer = new SimpleTracer();
    var propagator = new W3cTestPropagator(tracer);
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new FirstMatchingCompositeObservationHandler(
                new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                new DefaultTracingObservationHandler(tracer)));
    List<EventEnvelope> sent = new CopyOnWriteArrayList<>();
    var publisher =
        new ObservedAsyncEventPublisher(
            (destination, envelope) -> {
              sent.add(envelope);
              return CompletableFuture.completedFuture(
                  new PublishResult(envelope.id(), destination, "accepted"));
            },
            registry,
            properties());
    var stored =
        event()
            .withExtension(
                EventEnvelope.OUTBOX_TRACEPARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
            .withExtension(EventEnvelope.OUTBOX_TRACESTATE, "bank=test");
    publisher.publish("orders", stored).toCompletableFuture().join();
    assertTrue(sent.getFirst().extension(EventEnvelope.OUTBOX_TRACEPARENT).isEmpty());
    assertTrue(sent.getFirst().extension(EventEnvelope.OUTBOX_TRACESTATE).isEmpty());
    assertTrue(
        sent.getFirst()
            .extension(EventEnvelope.TRACEPARENT)
            .orElseThrow()
            .contains("4bf92f3577b34da6a3ce929d0e0e4736"));
    var dispatch =
        tracer.getSpans().stream()
            .filter(span -> "outbox dispatch orders".equals(span.getName()))
            .findFirst()
            .orElseThrow();
    var publish =
        tracer.getSpans().stream()
            .filter(span -> "publish orders".equals(span.getName()))
            .findFirst()
            .orElseThrow();
    assertEquals("00f067aa0ba902b7", dispatch.context().parentId());
    assertEquals(dispatch.context().spanId(), publish.context().parentId());
  }

  @Test
  void unsupportedDestinationsDoNotEnterTracingOrExposeRawAddressInErrors() {
    var registry = ObservationRegistry.create();
    AtomicInteger started = new AtomicInteger();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStart(Observation.Context context) {
                started.incrementAndGet();
              }
            });
    var empty = new RoutingAsyncEventPublisher(List.of());
    var publisher = new ObservedAsyncEventPublisher(empty, registry, properties());
    assertThat(
            assertThrows(
                    CompletionException.class,
                    () ->
                        publisher
                            .publish("https://user:do-not-leak@sqs.local/q", event())
                            .toCompletableFuture()
                            .join())
                .getCause())
        .hasMessageNotContaining("do-not-leak");
    assertEquals(0, started.get());
    runner()
        .withPropertyValues(
            "axiz.messaging.destinations.orders=https://user:do-not-leak@sqs.local/q")
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              var stack = new StringWriter();
              Objects.requireNonNull(ctx.getStartupFailure())
                  .printStackTrace(new PrintWriter(stack));
              assertThat(stack.toString()).doesNotContain("do-not-leak");
            });
  }

  private static ReactiveWebApplicationContextRunner runner() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingAwsAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class));
  }

  @Test
  void nativeWiringHonorsOverridesDisableAndRejectsUnsupportedConfiguredDestinations() {
    runner()
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveEvents.class)
                    .doesNotHaveBean(SqsAsyncClient.class)
                    .doesNotHaveBean(SnsAsyncClient.class));
    runner()
        .withPropertyValues("axiz.messaging.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(ReactiveEvents.class));
    runner()
        .withPropertyValues("axiz.messaging.destinations.orders=kafka:orders")
        .run(ctx -> assertThat(ctx).hasFailed());
    runner()
        .withBean(
            AsyncEventPublisher.class,
            () ->
                (destination, event) ->
                    CompletableFuture.completedFuture(
                        new PublishResult(event.id(), destination, null)))
        .withPropertyValues("axiz.messaging.destinations.orders=kafka:orders")
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void configuredAwsDestinationsOwnAndCloseTheirStartupResources() {
    runner()
        .withSystemProperties("aws.accessKeyId=local-test", "aws.secretAccessKey=local-test")
        .withPropertyValues(
            "axiz.aws.region=us-east-1",
            "axiz.messaging.aws.api-call-timeout=7s",
            "axiz.messaging.destinations.queue=http://localhost:4566/000000000000/queue",
            "axiz.messaging.destinations.topic=arn:aws:sns:us-east-1:000000000000:topic")
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(SqsAsyncClient.class)
                  .hasSingleBean(SnsAsyncClient.class)
                  .hasBean("axizMessagingSqsAsyncResources")
                  .hasBean("axizMessagingSnsAsyncResources");
              assertThat(
                      ctx.getBean(SqsAsyncClient.class)
                          .serviceClientConfiguration()
                          .overrideConfiguration()
                          .apiCallTimeout())
                  .contains(Duration.ofSeconds(7));
              assertThat(
                      ctx.getBean(SnsAsyncClient.class)
                          .serviceClientConfiguration()
                          .overrideConfiguration()
                          .apiCallTimeout())
                  .contains(Duration.ofSeconds(7));
            });
  }

  @Test
  void suppliedAwsClientsAreBorrowedAndNoOwnerIsCreated() {
    SqsAsyncClient sqs =
        new SqsAsyncClient() {
          public String serviceName() {
            return "sqs";
          }

          public void close() {}
        };
    SnsAsyncClient sns =
        new SnsAsyncClient() {
          public String serviceName() {
            return "sns";
          }

          public void close() {}
        };
    runner()
        .withBean(SqsAsyncClient.class, () -> sqs)
        .withBean(SnsAsyncClient.class, () -> sns)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean("axizMessagingSqsAsyncResources")
                  .doesNotHaveBean("axizMessagingSnsAsyncResources");
              assertThat(
                      ctx.getBeansOfType(AsyncEventPublisherAdapter.class).values().stream()
                          .map(AsyncEventPublisherAdapter::name))
                  .containsExactlyInAnyOrder("sqs", "sns");
            });
  }

  private static EventFactory factory() {
    return new EventFactory(
        "urn:service",
        "",
        new JacksonPayloadCodec(JsonMapper.builder().build()),
        () -> "id",
        Clock.systemUTC());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException error) {
      throw new AssertionError(error);
    }
  }
}
