package pe.axiz.core.commons;

import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Metadata of the request or message being processed by the current thread.
 *
 * <p>Inbound adapters (HTTP filters, message listeners) open a scope; outbound adapters read it to
 * propagate headers. Reactive code carries the value in the Reactor context under {@link #KEY} and
 * the Spring layer bridges both through Micrometer context-propagation.
 */
public final class RequestContext {

  /** Key used in the Reactor context and by the context-propagation accessor. */
  public static final String KEY = "axiz.request";

  private static final ThreadLocal<@Nullable RequestMetadata> CURRENT = new ThreadLocal<>();

  private RequestContext() {}

  public static Optional<RequestMetadata> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static @Nullable RequestMetadata get() {
    return CURRENT.get();
  }

  /** Sets the value for the current thread and returns a scope that restores the previous one. */
  public static Scope open(RequestMetadata metadata) {
    RequestMetadata previous = CURRENT.get();
    CURRENT.set(Checks.notNull(metadata, "metadata"));
    return () -> restore(previous);
  }

  /** Runs a task with the given metadata. */
  public static <T> T call(RequestMetadata metadata, Callable<T> task) throws Exception {
    try (Scope ignored = open(metadata)) {
      return task.call();
    }
  }

  /** Low-level setter for context-propagation accessors; prefer {@link #open}. */
  public static void restore(@Nullable RequestMetadata value) {
    if (value == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(value);
    }
  }

  /** Closes a scope without checked exceptions. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
