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
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;
import pe.axiz.core.errors.RateLimitedException;
import pe.axiz.core.id.CorrelationIds;

/**
 * Turns any failure into a problem and logs it once with bounded diagnostic metadata. Exception
 * messages, throwable objects and request paths may contain secrets and are never logged. Server
 * errors use ERROR; failed dependencies and denied access WARN; conflicts INFO; other errors DEBUG.
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

  /** Builds the problem and logs the failure. */
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
   * Zero means no explicit delay; never use an arbitrary provider message or parameter as a header.
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

  /** Applies only validated correlation metadata and a typed, bounded 429 delay. */
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
            "status={} code={} correlationId={} exceptionType={}",
            problem.status(),
            problem.code(),
            problem.correlationId(),
            failure.getClass().getName());
  }
}
