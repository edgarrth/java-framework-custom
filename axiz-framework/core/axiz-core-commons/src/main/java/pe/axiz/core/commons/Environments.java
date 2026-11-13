package pe.axiz.core.commons;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Operational profile of a deployment ({@code axiz.config.environment}). State kept in one process
 * (in-memory idempotency keys, locks, object storage) is only correct with a single replica and no
 * restarts, so it is accepted in {@link #SINGLE_PROCESS} environments and rejected anywhere else: a
 * deployed environment must use a shared store for the guarantees the framework declares.
 *
 * <p>This is also the common allowlist for explicitly requested transport exceptions (HTTP and
 * data-store TLS), embedded databases, schema-changing JPA modes, credentialed CORS policy and
 * local audit defaults. Active Spring profiles never grant these exceptions. Each integration must
 * still require its own explicit exception flag or selector where applicable.
 */
public final class Environments {

  /** Environments where per-process state is acceptable. */
  public static final Set<String> SINGLE_PROCESS = Set.of("local", "test", "ci");

  private Environments() {}

  public static boolean allowsInMemoryState(@Nullable String environment) {
    return environment != null
        && SINGLE_PROCESS.contains(environment.strip().toLowerCase(Locale.ROOT));
  }

  /**
   * Fails when {@code environment} is a deployed one, or is not set.
   *
   * @param setting the property that selected the in-memory implementation, e.g. {@code
   *     axiz.lock.store=memory}
   * @param alternatives what to use instead, e.g. {@code redis or dynamodb}
   * @param reason the guarantee that a per-process store would break
   */
  public static void requireSingleProcess(
      @Nullable String environment, String setting, String alternatives, String reason) {
    if (!allowsInMemoryState(environment)) {
      // an unset environment is never taken for a local one: a deployment that lost the setting
      // must not fall back to per-process state
      String current =
          environment == null || environment.isBlank()
              ? "axiz.config.environment is not set; set it to local or test where one process"
                  + " runs"
              : "axiz.config.environment=" + environment;
      throw new IllegalStateException(
          setting
              + " is only allowed in "
              + SINGLE_PROCESS.stream().sorted().toList()
              + " ("
              + current
              + "): "
              + reason
              + "; use "
              + alternatives);
    }
  }
}
