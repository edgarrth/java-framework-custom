package pe.axiz.spring.foundation;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.util.ClassUtils;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.Stages;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Native stage subscriptions with explicitly scoped context, without global Reactor hooks. */
public final class ReactiveStages {
  private static final boolean CONTEXT_AVAILABLE =
      ClassUtils.isPresent(
          "io.micrometer.context.ContextSnapshotFactory", ReactiveStages.class.getClassLoader());

  private ReactiveStages() {}

  /** Resolves optional context accessors before the first application request. */
  public static void initialize() {
    if (CONTEXT_AVAILABLE) Micrometer.initialize();
  }

  /** Defers submission and cancels the native stage when its subscriber cancels. */
  public static <T> Mono<T> from(Supplier<? extends CompletionStage<T>> work) {
    Objects.requireNonNull(work);
    return Mono.deferContextual(
        context ->
            CONTEXT_AVAILABLE ? Micrometer.from(context, work) : Mono.fromCompletionStage(work));
  }

  /** Scopes streaming submission, demand, cancellation and signals for this subscriber. */
  public static <T> Flux<T> flux(Supplier<? extends Publisher<T>> work) {
    Objects.requireNonNull(work);
    return Flux.deferContextual(
        context -> CONTEXT_AVAILABLE ? Micrometer.flux(context, work) : Flux.from(work.get()));
  }

  private static final class Micrometer {
    private static final io.micrometer.context.ContextSnapshotFactory FACTORY =
        io.micrometer.context.ContextSnapshotFactory.builder().clearMissing(true).build();
    private static final Stages.ContextProvider PROVIDER =
        () -> {
          var snapshot = FACTORY.captureAll();
          return () -> snapshot.setThreadLocals()::close;
        };

    static void initialize() {
      FACTORY.captureAll();
    }

    static <T> Mono<T> from(ContextView context, Supplier<? extends CompletionStage<T>> work) {
      var snapshot = FACTORY.captureFrom(context);
      Supplier<RequestContext.Scope> scopes = () -> snapshot.setThreadLocals()::close;
      Mono<T> source =
          Mono.fromCompletionStage(
              () -> {
                try (var values = scopes.get();
                    var binding = Stages.context(PROVIDER)) {
                  return work.get();
                }
              });
      return source.transform(
          Operators.<T, T>lift((ignored, actual) -> new ScopedSubscriber<>(actual, scopes)));
    }

    static <T> Flux<T> flux(ContextView context, Supplier<? extends Publisher<T>> work) {
      var snapshot = FACTORY.captureFrom(context);
      Supplier<RequestContext.Scope> scopes = () -> snapshot.setThreadLocals()::close;
      Flux<T> source =
          Flux.defer(
              () -> {
                try (var values = scopes.get();
                    var binding = Stages.context(PROVIDER)) {
                  return Flux.from(work.get());
                }
              });
      return source.transform(
          Operators.<T, T>lift((ignored, actual) -> new ScopedSubscriber<>(actual, scopes)));
    }

    private static final class ScopedSubscriber<T> implements CoreSubscriber<T> {
      private final CoreSubscriber<? super T> actual;
      private final Supplier<RequestContext.Scope> scopes;

      private ScopedSubscriber(
          CoreSubscriber<? super T> actual, Supplier<RequestContext.Scope> scopes) {
        this.actual = actual;
        this.scopes = scopes;
      }

      private void run(Runnable action) {
        try (var values = scopes.get();
            var binding = Stages.context(PROVIDER)) {
          action.run();
        }
      }

      @Override
      public Context currentContext() {
        return actual.currentContext();
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        run(
            () ->
                actual.onSubscribe(
                    new Subscription() {
                      @Override
                      public void request(long count) {
                        run(() -> subscription.request(count));
                      }

                      @Override
                      public void cancel() {
                        run(subscription::cancel);
                      }
                    }));
      }

      @Override
      public void onNext(T value) {
        run(() -> actual.onNext(value));
      }

      @Override
      public void onError(Throwable error) {
        run(() -> actual.onError(error));
      }

      @Override
      public void onComplete() {
        run(actual::onComplete);
      }
    }
  }
}
