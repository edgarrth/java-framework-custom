package pe.axiz.spring.errors;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import pe.axiz.core.api.StandardHeaders;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorType;
import pe.axiz.core.errors.Failures;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.id.CorrelationIds;

/**
 * Convierte cualquier fallo en un problema y lo registra una vez con metadatos de diagnóstico
 * acotados. Los mensajes de excepción, los objetos throwable y las rutas de solicitud pueden
 * contener secretos y nunca se registran. Los errores del servidor usan ERROR; las dependencias
 * fallidas y el acceso denegado, WARN; los conflictos, INFO; los demás errores, DEBUG.
 */
public class ProblemResponder {

  public static final String MEDIA_TYPE = Problem.MEDIA_TYPE;
  private static final Logger LOG = LoggerFactory.getLogger("axiz.errors");

  private final ProblemFactory factory;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ProblemResponder(ProblemFactory factory) {
    this.factory = Objects.requireNonNull(factory);
  }

  /** Construye el problema y registra el fallo. */
  public Problem problem(
      Throwable failure, Locale locale, @Nullable String path, @Nullable String correlationId) {
    AxizException axiz = factory.toAxizException(failure);
    var metadata = RequestContext.get();
    String safeCorrelation =
        CorrelationIds.reuseOrCreate(
            metadata != null
                ? metadata.correlationId()
                : correlationId != null ? correlationId : MDC.get("correlationId"));
    Problem problem = factory.create(axiz, locale, path, safeCorrelation);
    log(axiz, problem, failure);
    return problem;
  }

  public String json(Problem problem) {
    return ProblemJson.write(problem);
  }

  /**
   * Cero significa sin retraso explícito; nunca se usa como cabecera un mensaje o parámetro
   * arbitrario del proveedor.
   */
  static long retryAfterSeconds(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 64; depth++) {
      if (current instanceof AxizException)
        return current instanceof RateLimitedException limited ? limited.retryAfterSeconds() : 0;
      current = current.getCause();
    }
    return 0;
  }

  /** Aplica solo metadatos de correlación validados y un retraso 429 tipado y acotado. */
  public void headers(HttpHeaders headers, Problem problem, Throwable failure) {
    String correlation = problem.correlationId();
    if (correlation != null) headers.set(StandardHeaders.CORRELATION_ID, correlation);
    long delay = retryAfterSeconds(failure);
    if (problem.status() == 429 && delay > 0) headers.set("Retry-After", Long.toString(delay));
  }

  private static void log(AxizException axiz, Problem problem, Throwable failure) {
    Level level;
    if (problem.status() >= 500) {
      level = axiz.type() == ErrorType.EXTERNAL ? Level.WARN : Level.ERROR;
    } else if (axiz.type() == ErrorType.SECURITY) {
      level = Level.WARN;
    } else if (axiz.category() == ErrorCategory.CONFLICT) {
      level = Level.INFO;
    } else {
      level = Level.DEBUG;
    }
    LOG.atLevel(level)
        .log(
            "status={} code={} correlationId={} exceptionType={} failure={}",
            problem.status(),
            problem.code(),
            problem.correlationId(),
            failure.getClass().getName(),
            Failures.describe(failure));
  }
}
