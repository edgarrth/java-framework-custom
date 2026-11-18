package pe.axiz.spring.audit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.audit.AsyncStrictAuditPublisher;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import pe.axiz.core.audit.StrictAuditPublisher;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.spring.foundation.BoundedAsyncDispatcher;

/** One bounded confirmation boundary; no fallback is allowed to turn failure into success. */
final class StrictAuditPipeline implements AsyncStrictAuditPublisher, AutoCloseable {
  private final BoundedAsyncDispatcher dispatcher;
  private final Function<AuditEvent, CompletionStage<Void>> publication;
  private final Duration timeout;
  private final Stages.ContextProvider contextProvider = AuditDeliveryContext.PROVIDER;

  StrictAuditPipeline(
      AxizAuditProperties.Strict properties,
      Function<AuditEvent, CompletionStage<Void>> publication,
      @Nullable MeterRegistry registry) {
    this.publication = Objects.requireNonNull(publication);
    this.timeout = properties.timeout();
    this.dispatcher =
        new BoundedAsyncDispatcher("strict-audit", properties.concurrency(), 0, timeout, registry);
  }

  @Override
  public CompletionStage<Void> publish(AuditEvent event) {
    Objects.requireNonNull(event);
    var context = contextProvider.capture();
    return dispatcher.submit(
        () -> {
          try (var ignored = context.get()) {
            return publication.apply(event);
          }
        });
  }

  void await(AuditEvent event) {
    var confirmation = publish(event).toCompletableFuture();
    try {
      confirmation.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      confirmation.cancel(true);
      Thread.currentThread().interrupt();
      throw new AuditPublishException("Strict audit confirmation interrupted", interrupted);
    } catch (TimeoutException elapsed) {
      confirmation.cancel(true);
      throw new AuditPublishException("Strict audit confirmation deadline elapsed", elapsed);
    } catch (ExecutionException failure) {
      throw new AuditPublishException("Strict audit was not confirmed", Stages.unwrap(failure));
    }
  }

  @Override
  public void close() {
    dispatcher.close();
  }

  /** Keeps synchronous and asynchronous ports distinct during Spring bean selection. */
  static final class Imperative implements StrictAuditPublisher, AutoCloseable {
    private final StrictAuditPipeline pipeline;

    Imperative(StrictAuditPipeline pipeline) {
      this.pipeline = pipeline;
    }

    @Override
    public void publish(AuditEvent event) {
      pipeline.await(event);
    }

    @Override
    public void close() {
      pipeline.close();
    }
  }
}
