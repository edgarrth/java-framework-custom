package pe.axiz.spring.messaging;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.messaging.AsyncEventPublisher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;

/**
 * Native producer observations and logical destinations. Scopes cover only submission/callback,
 * never the asynchronous wait; cancelled sends stop observations and cancel native I/O.
 */
public final class ObservedAsyncEventPublisher implements AsyncEventPublisher {
  private final AsyncEventPublisher delegate;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Native producer borrows the application's live ObservationRegistry. "
              + "Identity/lifecycle and tracing handlers are intentionally shared, just like "
              + "ObservedEventPublisher; there is no getter.")
  private final ObservationRegistry registry;

  private final AxizMessagingProperties properties;

  public ObservedAsyncEventPublisher(
      AsyncEventPublisher delegate,
      ObservationRegistry registry,
      AxizMessagingProperties properties) {
    this.delegate = Objects.requireNonNull(delegate);
    this.registry = Objects.requireNonNull(registry);
    this.properties = Objects.requireNonNull(properties);
  }

  @Override
  public boolean supports(String destination) {
    return delegate.supports(properties.resolve(destination));
  }

  @Override
  public CompletionStage<PublishResult> publish(String destination, EventEnvelope event) {
    if (!supports(destination))
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("No native publisher supports the requested destination"));
    String stored = event.extension(EventEnvelope.OUTBOX_TRACEPARENT).orElse(null);
    if (stored == null) return send(destination, event);
    Map<String, String> carrier = new HashMap<>();
    carrier.put(EventEnvelope.TRACEPARENT, stored);
    event
        .extension(EventEnvelope.OUTBOX_TRACESTATE)
        .ifPresent(value -> carrier.put(EventEnvelope.TRACESTATE, value));
    ReceiverContext<Map<String, String>> context =
        new ReceiverContext<>((values, key) -> values == null ? null : values.get(key));
    context.setCarrier(carrier);
    context.setRemoteServiceName("outbox");
    Observation dispatch =
        Observation.createNotStarted(AxizObservations.OUTBOX_DISPATCH, () -> context, registry)
            .contextualName("outbox dispatch " + destination)
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.DESTINATION, destination))
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, event.type()))
            .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, event.id()));
    return observed(
        dispatch,
        () ->
            send(
                destination,
                event.withoutExtensions(
                    EventEnvelope.OUTBOX_TRACEPARENT, EventEnvelope.OUTBOX_TRACESTATE)));
  }

  private CompletionStage<PublishResult> send(String destination, EventEnvelope event) {
    String physical = properties.resolve(destination);
    Map<String, String> carrier = new HashMap<>();
    SenderContext<Map<String, String>> context =
        new SenderContext<>(
            (values, key, value) -> {
              if (values != null) values.put(key, value);
            });
    context.setCarrier(carrier);
    context.setRemoteServiceName(destination);
    Observation observation =
        Observation.createNotStarted(AxizObservations.EVENT_PUBLISH, () -> context, registry)
            .contextualName("publish " + destination)
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.DESTINATION, destination))
            .lowCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_TYPE, event.type()))
            .highCardinalityKeyValue(KeyValue.of(AxizAttributes.EVENT_ID, event.id()));
    return observed(
        observation,
        () -> delegate.publish(physical, ObservedEventPublisher.withTrace(event, carrier)));
  }

  private static CompletionStage<PublishResult> observed(
      Observation observation, Supplier<CompletionStage<PublishResult>> work) {
    observation.start();
    AtomicBoolean stopped = new AtomicBoolean();
    @Nullable CompletionStage<PublishResult> submitted = null;
    try (Observation.Scope ignored = observation.openScope()) {
      CompletionStage<PublishResult> source = Stages.call(work);
      submitted = source;
      CompletableFuture<PublishResult> result =
          new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean interrupt) {
              boolean cancelled = super.cancel(interrupt);
              if (cancelled) source.toCompletableFuture().cancel(interrupt);
              return cancelled;
            }
          };
      Stages.observe(
          source,
          (value, error) -> {
            if (stopped.compareAndSet(false, true)) {
              if (error != null) observation.error(Stages.unwrap(error));
              observation.stop();
            }
            if (error == null) result.complete(value);
            else result.completeExceptionally(Stages.unwrap(error));
          },
          failure -> {
            if (stopped.compareAndSet(false, true)) {
              observation.error(failure);
              observation.stop();
            }
            result.completeExceptionally(failure);
            source.toCompletableFuture().cancel(true);
          });
      return result;
    } catch (RuntimeException | Error failure) {
      if (submitted != null) submitted.toCompletableFuture().cancel(true);
      if (stopped.compareAndSet(false, true)) {
        observation.error(failure);
        observation.stop();
      }
      return CompletableFuture.failedFuture(failure);
    }
  }
}
