package pe.axiz.core.errors;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Log-safe description of a failure: the chain of causes with each type and the frame that threw
 * it, so an operator can locate the cause without a debugger.
 *
 * <p>Messages are never included: a driver, a parser or a service may echo the values it rejected
 * (a duplicate e-mail, a fragment of a request body, a card number). Types and stack frames carry
 * no request data. When a failure is thrown inside the JDK, the first frame outside it is added, so
 * {@code NullPointerException at java.util.Objects.requireNonNull(...)} also says who called it.
 *
 * <pre>
 * java.lang.IllegalStateException at pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore.run(
 *     JdbcSagaStateStore.java:232) | caused by org.postgresql.util.PSQLException at
 *     org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2733)
 * </pre>
 */
public final class Failures {

  /** Causes described at most; deeper chains end with an ellipsis. */
  public static final int MAX_CAUSES = 6;

  private static final List<String> PLATFORM =
      List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

  private Failures() {}

  /** One entry per cause, joined by {@code " | caused by "}; never null, never throws. */
  public static String describe(@Nullable Throwable failure) {
    if (failure == null) return "none";
    StringBuilder out = new StringBuilder(256);
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    int depth = 0;
    while (current != null && seen.add(current)) {
      if (depth == MAX_CAUSES) {
        out.append(" | caused by ...");
        break;
      }
      if (depth > 0) out.append(" | caused by ");
      describeOne(current, out);
      current = current.getCause();
      depth++;
    }
    return out.toString();
  }

  /** The innermost cause, for metrics and conditions that depend on the original failure. */
  public static Throwable rootCause(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    while (current.getCause() != null && seen.add(current)) current = current.getCause();
    return current;
  }

  private static void describeOne(Throwable failure, StringBuilder out) {
    out.append(failure.getClass().getName());
    StackTraceElement[] frames = frames(failure);
    if (frames.length == 0) return;
    out.append(" at ").append(frames[0]);
    if (platform(frames[0])) {
      for (int i = 1; i < frames.length; i++) {
        if (!platform(frames[i])) {
          out.append(" via ").append(frames[i]);
          break;
        }
      }
    }
  }

  private static StackTraceElement[] frames(Throwable failure) {
    try {
      return failure.getStackTrace();
    } catch (RuntimeException e) {
      return new StackTraceElement[0];
    }
  }

  private static boolean platform(StackTraceElement frame) {
    String type = frame.getClassName();
    return PLATFORM.stream().anyMatch(type::startsWith);
  }
}
