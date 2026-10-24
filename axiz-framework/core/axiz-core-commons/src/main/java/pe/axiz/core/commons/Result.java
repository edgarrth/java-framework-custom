package pe.axiz.core.commons;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Outcome of an operation that either succeeds with a value or fails with a business error.
 *
 * <p>Use cases return {@code Result} instead of throwing exceptions for expected business outcomes;
 * exceptions remain for technical failures.
 *
 * @param <T> success value type
 * @param <E> error type
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

  /** Successful result. */
  record Ok<T, E>(T value) implements Result<T, E> {
    public Ok {
      Objects.requireNonNull(value, "value");
    }
  }

  /** Failed result. */
  record Err<T, E>(E error) implements Result<T, E> {
    public Err {
      Objects.requireNonNull(error, "error");
    }
  }

  static <T, E> Result<T, E> ok(T value) {
    return new Ok<>(value);
  }

  static <T, E> Result<T, E> err(E error) {
    return new Err<>(error);
  }

  /**
   * Runs a supplier and converts any exception into an error with the given mapper.
   *
   * @param supplier code to run
   * @param errorMapper converts the exception into the error type
   * @return ok with the value or err with the mapped exception
   */
  static <T, E> Result<T, E> catching(
      Supplier<? extends T> supplier, Function<? super Exception, ? extends E> errorMapper) {
    try {
      return ok(supplier.get());
    } catch (RuntimeException e) {
      return err(errorMapper.apply(e));
    }
  }

  default boolean isOk() {
    return this instanceof Ok;
  }

  default boolean isErr() {
    return this instanceof Err;
  }

  default Optional<T> success() {
    return this instanceof Ok<T, E> ok ? Optional.of(ok.value()) : Optional.empty();
  }

  default Optional<E> failure() {
    return this instanceof Err<T, E> err ? Optional.of(err.error()) : Optional.empty();
  }

  default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
    return switch (this) {
      case Ok<T, E> ok -> ok(mapper.apply(ok.value()));
      case Err<T, E> err -> err(err.error());
    };
  }

  default <F> Result<T, F> mapError(Function<? super E, ? extends F> mapper) {
    return switch (this) {
      case Ok<T, E> ok -> ok(ok.value());
      case Err<T, E> err -> err(mapper.apply(err.error()));
    };
  }

  default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper) {
    return switch (this) {
      case Ok<T, E> ok -> Objects.requireNonNull(mapper.apply(ok.value()), "mapper result");
      case Err<T, E> err -> err(err.error());
    };
  }

  default <R> R fold(
      Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
    return switch (this) {
      case Ok<T, E> ok -> onOk.apply(ok.value());
      case Err<T, E> err -> onErr.apply(err.error());
    };
  }

  default T orElse(T fallback) {
    return this instanceof Ok<T, E> ok ? ok.value() : fallback;
  }

  default T orElseGet(Function<? super E, ? extends T> fallback) {
    return switch (this) {
      case Ok<T, E> ok -> ok.value();
      case Err<T, E> err -> fallback.apply(err.error());
    };
  }

  /**
   * Returns the value or throws the exception produced from the error.
   *
   * @param exceptionMapper builds the exception to throw
   * @param <X> exception type
   * @return the success value
   * @throws X when the result is an error
   */
  default <X extends Exception> T orElseThrow(Function<? super E, X> exceptionMapper) throws X {
    return switch (this) {
      case Ok<T, E> ok -> ok.value();
      case Err<T, E> err -> throw exceptionMapper.apply(err.error());
    };
  }

  default Result<T, E> peek(Consumer<? super T> action) {
    if (this instanceof Ok<T, E> ok) {
      action.accept(ok.value());
    }
    return this;
  }

  default Result<T, E> peekError(Consumer<? super E> action) {
    if (this instanceof Err<T, E> err) {
      action.accept(err.error());
    }
    return this;
  }
}
