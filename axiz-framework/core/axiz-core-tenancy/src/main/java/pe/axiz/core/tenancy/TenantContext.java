package pe.axiz.core.tenancy;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Tenant of the current unit of work, bound for the duration of a call. The binding is restored
 * when the call ends, so nested calls and thread reuse never leak a tenant. Works with platform and
 * virtual threads; reactive code propagates it through the Reactor context (starter).
 */
public final class TenantContext {

  private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
  private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Optional<String> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Current tenant or {@link TenantRequiredException}. */
  public static String require() {
    String tenant = CURRENT.get();
    if (tenant == null) {
      throw new TenantRequiredException();
    }
    return tenant;
  }

  public static boolean isValid(@Nullable String tenant) {
    return tenant != null && VALID.matcher(tenant).matches();
  }

  /**
   * Runs a call bound to a tenant.
   *
   * @param tenant tenant id (lowercase, digits and dashes)
   * @param call work to run
   * @return the call result
   * @throws Exception whatever the call throws
   */
  public static <T> T callAs(String tenant, Callable<T> call) throws Exception {
    if (!isValid(tenant)) {
      throw new IllegalArgumentException("Invalid tenant id");
    }
    String previous = CURRENT.get();
    CURRENT.set(tenant);
    try {
      return call.call();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  public static void runAs(String tenant, Runnable runnable) {
    try {
      callAs(
          tenant,
          () -> {
            runnable.run();
            return null;
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Low-level setter for context-propagation accessors and filters; prefer {@link #callAs}.
   *
   * @param tenant tenant to bind, or null to clear
   */
  public static void restore(@Nullable String tenant) {
    if (tenant == null) {
      CURRENT.remove();
    } else if (isValid(tenant)) {
      CURRENT.set(tenant);
    } else {
      throw new IllegalArgumentException("Invalid tenant id");
    }
  }

  /** Wraps a task so it runs with the tenant that is current now (for executors). */
  public static Runnable propagate(Runnable task) {
    String tenant = CURRENT.get();
    return tenant == null ? task : () -> runAs(tenant, task);
  }
}
