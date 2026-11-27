package pe.axiz.core.logging;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.core.sensitive.SensitiveField;

/**
 * Escribe las líneas de entrada y salida de una operación. Los argumentos y los resultados se
 * enmascaran con el {@link ObjectMasker}; los parámetros marcados con {@link NoLog} se reemplazan
 * por un marcador.
 */
public final class OperationLogger {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.operation");

  private final ObjectMasker masker;

  public OperationLogger(ObjectMasker masker) {
    this.masker = masker;
  }

  /**
   * Registra en log el inicio de una operación.
   *
   * @param operation nombre de la operación
   * @param argumentNames nombres de los parámetros
   * @param arguments valores de los argumentos
   * @param excluded por argumento, indica si está marcado con {@link NoLog}
   * @param declared sensibilidad declarada en cada argumento, si existe
   * @param level nivel
   */
  public void entry(
      String operation,
      List<String> argumentNames,
      @Nullable Object[] arguments,
      boolean[] excluded,
      @Nullable SensitiveField[] declared,
      LogOperation.Level level) {
    if (!enabled(level)) {
      return;
    }
    Map<String, @Nullable Object> args = new LinkedHashMap<>();
    for (int i = 0; i < arguments.length; i++) {
      String name = i < argumentNames.size() ? argumentNames.get(i) : "arg" + i;
      args.put(
          name,
          i < excluded.length && excluded[i]
              ? "<not logged>"
              : masker.describeNamed(
                  name, arguments[i], i < declared.length ? declared[i] : null, OutputChannel.LOG));
    }
    write(level, "operation={} phase=start args={}", operation, args);
  }

  public void entry(String operation, LogOperation.Level level) {
    if (enabled(level)) {
      write(level, "operation={} phase=start", operation, null);
    }
  }

  /** Registra en log una salida exitosa. */
  public void success(
      String operation,
      Duration duration,
      @Nullable Object result,
      boolean logResult,
      LogOperation.Level level) {
    if (!enabled(level)) {
      return;
    }
    try (var mdc =
        ScopedMdc.of(LogFields.OUTCOME, "success")
            .with(LogFields.DURATION_MS, Long.toString(duration.toMillis()))) {
      if (logResult) {
        write(
            level,
            "operation={} phase=end outcome=success result={}",
            operation,
            masker.describe(result, OutputChannel.LOG));
      } else {
        write(level, "operation={} phase=end outcome=success", operation, null);
      }
    }
  }

  /**
   * Registra en log una salida fallida; los rechazos de negocio en WARN y todo lo demás en ERROR.
   */
  public void failure(
      String operation,
      Duration duration,
      Throwable error,
      boolean expected,
      @Nullable String errorCode) {
    try (var mdc =
        ScopedMdc.of(LogFields.OUTCOME, expected ? "rejected" : "error")
            .with(LogFields.DURATION_MS, Long.toString(duration.toMillis()))
            .with(LogFields.ERROR_CODE, errorCode)) {
      if (expected) {
        LOG.warn(
            "operation={} phase=end outcome=rejected error={}",
            operation,
            error.getClass().getSimpleName());
      } else {
        LOG.error(
            "operation={} phase=end outcome=error type={}",
            operation,
            error.getClass().getSimpleName());
      }
    }
  }

  private static boolean enabled(LogOperation.Level level) {
    return level == LogOperation.Level.DEBUG ? LOG.isDebugEnabled() : LOG.isInfoEnabled();
  }

  private static void write(
      LogOperation.Level level, String format, String operation, @Nullable Object arg) {
    if (level == LogOperation.Level.DEBUG) {
      LOG.debug(format, operation, arg);
    } else {
      LOG.info(format, operation, arg);
    }
  }
}
