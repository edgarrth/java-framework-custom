package pe.axiz.core.context;

import java.util.Optional;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.lang.Checks;

/**
 * Metadatos de la petición o del mensaje que procesa el hilo actual.
 *
 * <p>Los adaptadores de entrada (filtros HTTP, listeners de mensajes) abren un scope; los
 * adaptadores de salida lo leen para propagar cabeceras. El código reactivo lleva el valor en el
 * contexto de Reactor bajo {@link #KEY} y la capa de Spring enlaza ambos mediante
 * context-propagation de Micrometer.
 */
public final class RequestContext {

  /** Clave usada en el contexto de Reactor y por el accesor de context-propagation. */
  public static final String KEY = "axiz.request";

  private static final ThreadLocal<@Nullable RequestMetadata> CURRENT = new ThreadLocal<>();

  private RequestContext() {}

  public static Optional<RequestMetadata> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static @Nullable RequestMetadata get() {
    return CURRENT.get();
  }

  /** Fija el valor para el hilo actual y devuelve un scope que restaura el anterior. */
  public static Scope open(RequestMetadata metadata) {
    RequestMetadata previous = CURRENT.get();
    CURRENT.set(Checks.notNull(metadata, "metadata"));
    return () -> restore(previous);
  }

  /** Ejecuta una tarea con los metadatos indicados. */
  public static <T> T call(RequestMetadata metadata, Callable<T> task) throws Exception {
    try (Scope ignored = open(metadata)) {
      return task.call();
    }
  }

  /** Setter de bajo nivel para accesores de context-propagation; es preferible {@link #open}. */
  public static void restore(@Nullable RequestMetadata value) {
    if (value == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(value);
    }
  }

  /** Cierra un scope sin excepciones comprobadas. */
  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
