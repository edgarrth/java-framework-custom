package pe.axiz.core.logging;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Puts values in the MDC for a scope and restores the previous values on close:
 *
 * <pre>{@code
 * try (var mdc = ScopedMdc.of(LogFields.OPERATION, "create-order")) { ... }
 * }</pre>
 *
 * <p>Values with CR/LF are sanitized to prevent log injection. Null values remove the key for the
 * scope.
 */
public final class ScopedMdc implements AutoCloseable {

  private static final int MAX_VALUE = 256;

  private final Map<String, @Nullable String> previous = new HashMap<>();

  private ScopedMdc() {}

  public static ScopedMdc of(String key, @Nullable String value) {
    return new ScopedMdc().with(key, value);
  }

  public static ScopedMdc of(Map<String, String> values) {
    ScopedMdc scope = new ScopedMdc();
    values.forEach(scope::with);
    return scope;
  }

  /** Adds another value to the same scope. */
  public ScopedMdc with(String key, @Nullable String value) {
    if (!previous.containsKey(key)) {
      previous.put(key, MDC.get(key));
    }
    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, sanitize(value));
    }
    return this;
  }

  static String sanitize(String value) {
    String clean = value.replace('\r', '_').replace('\n', '_');
    return clean.length() > MAX_VALUE ? clean.substring(0, MAX_VALUE) : clean;
  }

  @Override
  public void close() {
    previous.forEach(
        (k, v) -> {
          if (v == null) {
            MDC.remove(k);
          } else {
            MDC.put(k, v);
          }
        });
  }
}
