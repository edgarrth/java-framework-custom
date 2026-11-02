package pe.axiz.structure.bff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;

/**
 * Calls several dependencies in parallel on virtual threads and composes the result. Required parts
 * fail the composition; optional parts degrade to their fallback and are reported, so the channel
 * can show partial data. Every part shares one deadline.
 */
public final class Composer {

  private final Duration timeout;
  private final Function<Callable<Object>, Callable<Object>> contextPropagation;
  private final Map<String, Part> parts = new LinkedHashMap<>();

  private Composer(
      Duration timeout, Function<Callable<Object>, Callable<Object>> contextPropagation) {
    this.timeout = Objects.requireNonNull(timeout);
    this.contextPropagation = Objects.requireNonNull(contextPropagation);
  }

  /**
   * Starts a composition.
   *
   * @param timeout deadline of the whole composition
   * @param contextPropagation wraps each task so request context and traces follow it (the Spring
   *     starter supplies Micrometer context-propagation)
   * @return the composer
   */
  public static Composer within(
      Duration timeout, Function<Callable<Object>, Callable<Object>> contextPropagation) {
    return new Composer(timeout, contextPropagation);
  }

  public static Composer within(Duration timeout) {
    return new Composer(timeout, Function.identity());
  }

  public <T> Composer required(String name, Callable<T> call) {
    return add(name, call, true, null);
  }

  public <T> Composer optional(String name, Callable<T> call, Supplier<@Nullable T> fallback) {
    return add(name, call, false, fallback);
  }

  @SuppressWarnings("unchecked")
  private <T> Composer add(
      String name, Callable<T> call, boolean required, @Nullable Supplier<@Nullable T> fallback) {
    if (parts.putIfAbsent(
            name,
            new Part(
                (Callable<Object>) call,
                required,
                (Supplier<@Nullable Object>) (Supplier<?>) fallback))
        != null) {
      throw new IllegalArgumentException("Duplicate part " + name);
    }
    return this;
  }

  /** Runs all parts and waits until they finish or the deadline passes. */
  public Composition run() {
    long deadline = System.nanoTime() + timeout.toNanos();
    Map<String, @Nullable Object> values = new LinkedHashMap<>();
    List<String> degraded = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<String, Future<Object>> futures = new LinkedHashMap<>();
      parts.forEach(
          (name, part) ->
              futures.put(name, executor.submit(contextPropagation.apply(part.call()))));
      for (Map.Entry<String, Future<Object>> e : futures.entrySet()) {
        Part part = Objects.requireNonNull(parts.get(e.getKey()));
        try {
          long left = Math.max(0, deadline - System.nanoTime());
          values.put(e.getKey(), e.getValue().get(left, TimeUnit.NANOSECONDS));
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          futures.values().forEach(f -> f.cancel(true));
          throw new IllegalStateException("Interrupted while composing", ie);
        } catch (ExecutionException | TimeoutException failure) {
          e.getValue().cancel(true);
          if (part.required()) {
            futures.values().forEach(f -> f.cancel(true));
            throw failure(e.getKey(), failure);
          }
          degraded.add(e.getKey());
          @Nullable Supplier<@Nullable Object> fallback = part.fallback();
          values.put(e.getKey(), fallback != null ? fallback.get() : null);
        }
      }
    }
    return new Composition(values, degraded);
  }

  private static RuntimeException failure(String part, Exception failure) {
    Throwable cause =
        failure instanceof ExecutionException ee && ee.getCause() != null ? ee.getCause() : failure;
    if (cause instanceof RuntimeException re) {
      return re;
    }
    ExternalServiceException timeout =
        new ExternalServiceException(
            CoreErrors.EXTERNAL_TIMEOUT,
            new ExternalErrorDetail(part, -1, "timeout", null, ""),
            cause);
    return timeout;
  }

  private record Part(
      Callable<Object> call, boolean required, @Nullable Supplier<@Nullable Object> fallback) {}
}
