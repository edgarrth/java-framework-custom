package pe.axiz.core.tenancy;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Tenant de la unidad de trabajo actual, ligado durante una llamada. El enlace se restaura cuando
 * termina la llamada, de modo que las llamadas anidadas y la reutilización de hilos nunca filtran
 * un tenant. Funciona con hilos de plataforma y virtuales; el código reactivo lo propaga mediante
 * el contexto de Reactor (starter).
 */
public final class TenantContext {

  private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
  private static final ThreadLocal<@Nullable String> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  public static Optional<String> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Tenant actual o {@link TenantRequiredException}. */
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
   * Ejecuta una llamada ligada a un tenant.
   *
   * @param tenant id del tenant (minúsculas, dígitos y guiones)
   * @param call trabajo a ejecutar
   * @return el resultado de la llamada
   * @throws Exception lo que lance la llamada
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
   * Setter de bajo nivel para accesores de context-propagation y filtros; es preferible {@link
   * #callAs}.
   *
   * @param tenant tenant a ligar, o null para limpiar
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

  /**
   * Envuelve una tarea para que se ejecute con el tenant actual en este momento (para executors).
   */
  public static Runnable propagate(Runnable task) {
    String tenant = CURRENT.get();
    return tenant == null ? task : () -> runAs(tenant, task);
  }
}
