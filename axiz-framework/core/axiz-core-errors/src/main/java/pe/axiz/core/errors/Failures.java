package pe.axiz.core.errors;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Descripción de un fallo apta para logs: la cadena de causas con el tipo de cada una y el frame
 * que la lanzó, para que un operador pueda localizar la causa sin un depurador.
 *
 * <p>Nunca se incluyen los mensajes: un driver, un parser o un servicio pueden repetir los valores
 * que rechazaron (un e-mail duplicado, un fragmento del cuerpo de una petición, un número de
 * tarjeta). Los tipos y los frames de la pila no llevan datos de la petición. Cuando un fallo se
 * lanza dentro del JDK, se agrega el primer frame fuera de él, de modo que {@code
 * NullPointerException at java.util.Objects.requireNonNull(...)} también indica quién lo llamó.
 *
 * <pre>
 * java.lang.IllegalStateException at pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore.run(
 *     JdbcSagaStateStore.java:232) | caused by org.postgresql.util.PSQLException at
 *     org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2733)
 * </pre>
 */
public final class Failures {

  /** Máximo de causas descritas; las cadenas más profundas terminan con puntos suspensivos. */
  public static final int MAX_CAUSES = 6;

  private static final List<String> PLATFORM =
      List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

  private Failures() {}

  /**
   * Una entrada por causa, unidas con {@code " | caused by "}; nunca es null y nunca lanza
   * excepciones.
   */
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

  /** La causa más interna, para métricas y condiciones que dependen del fallo original. */
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
