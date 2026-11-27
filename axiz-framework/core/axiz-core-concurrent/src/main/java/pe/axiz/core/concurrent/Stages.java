package pe.axiz.core.concurrent;

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
import pe.axiz.core.context.RequestContext;

/** Compone E/S asíncrona nativa y propaga la cancelación aguas abajo a la operación activa. */
public final class Stages {
  private static final ThreadLocal<@Nullable ContextProvider> CONTEXT = new ThreadLocal<>();

  private Stages() {}

  /**
   * Suministra un snapshot inmutable para callbacks; los scopes se abren y cierran en el hilo que
   * ejecuta.
   */
  @FunctionalInterface
  public interface ContextProvider {
    Supplier<RequestContext.Scope> capture();
  }

  /**
   * Instala un proveedor de captura solo para el scope actual de envío o callback, nunca de forma
   * global.
   */
  public static RequestContext.Scope context(ContextProvider provider) {
    Objects.requireNonNull(provider);
    ContextProvider previous = CONTEXT.get();
    CONTEXT.set(provider);
    return () -> {
      if (previous == null) CONTEXT.remove();
      else CONTEXT.set(previous);
    };
  }

  /**
   * Registra una finalización con el contexto de su llamador e informa los fallos de contexto a su
   * propietario.
   */
  @SuppressWarnings(
      "FutureReturnValueIgnored") // Los fallos de callback y de scope se envían al propietario.
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

  /** Invoca E/S asíncrona nativa y convierte los fallos síncronos de envío en la etapa. */
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

  /**
   * Conversión pura de un valor propio. Siempre libera la entrada, incluida una finalización tardía
   * tras la cancelación, y descarta una salida que no pudo entregarse. El mapper devuelve un valor
   * no null independiente. Los callbacks solo de CPU corren en el hilo de finalización sin
   * restaurar el contexto ambiental, de modo que un accesor de contexto fallido no puede saltarse
   * la limpieza. Los callbacks de limpieza no deben lanzar excepciones.
   */
  @SuppressWarnings(
      "FutureReturnValueIgnored") // La finalización, la conversión y la limpieza se manejan aquí.
  public static <T, R> CompletionStage<R> consume(
      CompletionStage<T> source,
      Function<T, R> mapper,
      Consumer<T> releaseInput,
      Consumer<R> discardOutput) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(mapper);
    Objects.requireNonNull(releaseInput);
    Objects.requireNonNull(discardOutput);
    var result =
        new CompletableFuture<R>() {
          @Override
          public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            if (cancelled) source.toCompletableFuture().cancel(interrupt);
            return cancelled;
          }
        };
    source.whenComplete(
        (value, failure) -> {
          R answer = null;
          Throwable error = failure;
          try {
            if (error == null && !result.isDone())
              answer = Objects.requireNonNull(mapper.apply(Objects.requireNonNull(value)));
          } catch (RuntimeException | Error problem) {
            error = problem;
          } finally {
            if (value != null) {
              try {
                releaseInput.accept(value);
              } catch (RuntimeException | Error cleanup) {
                if (error == null) error = cleanup;
                else if (error != cleanup) error.addSuppressed(cleanup);
              }
            }
          }
          if (error != null) {
            result.completeExceptionally(unwrap(error));
            if (answer != null) discardOutput.accept(answer);
          } else if (answer != null && !result.complete(answer)) discardOutput.accept(answer);
        });
    return result;
  }

  /**
   * Adapta una finalización que admite null (por ejemplo una fuente reactiva vacía) a un valor
   * obligatorio.
   */
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

  /** Finalización sin payload, que preserva la cancelación de la operación nativa. */
  public static CompletionStage<Void> ignore(CompletionStage<?> source) {
    return compose(source, ignored -> CompletableFuture.completedFuture(null));
  }

  /**
   * Maneja el fallo de un efecto secundario de mejor esfuerzo sin inventar un payload Void no null.
   */
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
