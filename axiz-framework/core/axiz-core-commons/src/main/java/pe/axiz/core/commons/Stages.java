package pe.axiz.core.commons;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Composes native asynchronous I/O and propagates downstream cancellation to the active operation.
 */
public final class Stages {
  private static final ThreadLocal<@Nullable ContextProvider> CONTEXT = new ThreadLocal<>();

  private Stages() {}

  /**
   * Supplies an immutable callback snapshot; scopes are opened and closed on the executing thread.
   */
  @FunctionalInterface
  public interface ContextProvider {
    Supplier<RequestContext.Scope> capture();
  }

  /** Installs a capture provider only for the current submission/callback scope, never globally. */
  public static RequestContext.Scope context(ContextProvider provider) {
    Objects.requireNonNull(provider);
    ContextProvider previous = CONTEXT.get();
    CONTEXT.set(provider);
    return () -> {
      if (previous == null) CONTEXT.remove();
      else CONTEXT.set(previous);
    };
  }

  /** Registers a completion with its caller's context and reports context failures to its owner. */
  @SuppressWarnings(
      "FutureReturnValueIgnored") // Callback and scope failures are explicitly routed to the owner.
  public static <T extends @Nullable Object> void observe(
      CompletionStage<T> source,
      BiConsumer<? super T, ? super Throwable> callback,
      Consumer<Throwable> failure) {
    ContextProvider provider = CONTEXT.get();
    Supplier<RequestContext.Scope> snapshot;
    try {
      snapshot = provider == null ? () -> () -> {} : Objects.requireNonNull(provider.capture());
    } catch (RuntimeException | Error error) {
      failure.accept(error);
      return;
    }
    source.whenComplete(
        (value, error) -> {
          try (var values = Objects.requireNonNull(snapshot.get());
              RequestContext.Scope binding = provider == null ? () -> {} : context(provider)) {
            callback.accept(value, error);
          } catch (RuntimeException | Error problem) {
            failure.accept(problem);
          }
        });
  }

  /** Invokes native asynchronous I/O, turning synchronous submission failures into the stage. */
  public static <T> CompletionStage<T> call(Supplier<CompletionStage<T>> work) {
    Objects.requireNonNull(work);
    try {
      return Objects.requireNonNull(work.get());
    } catch (RuntimeException | Error failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  public static <T, R> CompletionStage<R> map(
      CompletionStage<T> source, Function<? super T, ? extends R> mapper) {
    return compose(source, value -> CompletableFuture.completedFuture(mapper.apply(value)));
  }

  /** Adapts a nullable completion (for example an empty reactive source) to a required value. */
  public static <T> CompletionStage<T> requireValue(CompletionStage<@Nullable T> source) {
    Objects.requireNonNull(source);
    CompletableFuture<T> result =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) source.toCompletableFuture().cancel(interrupt);
            return cancelled;
          }
        };
    observe(
        source,
        (value, error) -> {
          if (error != null) result.completeExceptionally(unwrap(error));
          else if (value == null)
            result.completeExceptionally(
                new IllegalStateException(
                    "Asynchronous operation completed without a required value"));
          else result.complete(value);
        },
        problem -> fail(result, source.toCompletableFuture(), problem));
    return result;
  }

  public static <T, R> CompletionStage<R> compose(
      CompletionStage<T> source, Function<? super T, ? extends CompletionStage<R>> next) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(next);
    AtomicReference<CompletableFuture<?>> active =
        new AtomicReference<>(source.toCompletableFuture());
    CompletableFuture<R> result =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) active.get().cancel(interrupt);
            return cancelled;
          }
        };
    observe(
        source,
        (value, error) -> {
          if (result.isDone()) return;
          if (error != null) {
            result.completeExceptionally(unwrap(error));
            return;
          }
          try {
            CompletionStage<R> stage = Objects.requireNonNull(next.apply(value));
            active.set(stage.toCompletableFuture());
            if (result.isCancelled()) {
              stage.toCompletableFuture().cancel(true);
              return;
            }
            observe(
                stage,
                (answer, failure) -> {
                  if (failure == null) result.complete(answer);
                  else result.completeExceptionally(unwrap(failure));
                },
                problem -> fail(result, active.get(), problem));
          } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
          }
        },
        problem -> fail(result, active.get(), problem));
    return result;
  }

  public static <T> CompletionStage<T> recover(
      CompletionStage<T> source, Function<Throwable, ? extends T> recovery) {
    CompletableFuture<T> result =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) source.toCompletableFuture().cancel(interrupt);
            return cancelled;
          }
        };
    observe(
        source,
        (value, error) -> {
          if (result.isDone()) return;
          try {
            result.complete(error == null ? value : recovery.apply(unwrap(error)));
          } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
          }
        },
        problem -> fail(result, source.toCompletableFuture(), problem));
    return result;
  }

  /** Completion without a payload, preserving cancellation of the native operation. */
  public static CompletionStage<Void> ignore(CompletionStage<?> source) {
    return compose(source, ignored -> CompletableFuture.completedFuture(null));
  }

  /** Handles a best-effort side effect failure without inventing a non-null Void payload. */
  public static CompletionStage<Void> settle(
      CompletionStage<Void> source, Consumer<Throwable> failure) {
    CompletableFuture<Void> result =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) source.toCompletableFuture().cancel(interrupt);
            return cancelled;
          }
        };
    observe(
        source,
        (ignored, error) -> {
          if (result.isDone()) return;
          try {
            if (error != null) failure.accept(unwrap(error));
            result.complete(null);
          } catch (RuntimeException | Error problem) {
            result.completeExceptionally(problem);
          }
        },
        problem -> fail(result, source.toCompletableFuture(), problem));
    return result;
  }

  public static Throwable unwrap(Throwable error) {
    Throwable cause = error.getCause();
    return (error instanceof CompletionException && cause != null) ? cause : error;
  }

  private static void fail(
      CompletableFuture<?> result, CompletableFuture<?> active, Throwable error) {
    if (result.completeExceptionally(error)) active.cancel(true);
  }
}
