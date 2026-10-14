package pe.axiz.spring.errors;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorCategory;
import pe.axiz.core.errors.ErrorType;
import pe.axiz.core.errors.Problem;
import pe.axiz.core.errors.ProblemFactory;
import pe.axiz.core.errors.ProblemJson;

/**
 * Turns any failure into a problem and logs it once: server errors at ERROR with the stack trace,
 * rejections and client errors at WARN or DEBUG without it.
 */
public class ProblemResponder {

  public static final String MEDIA_TYPE = Problem.MEDIA_TYPE;
  private static final Logger LOG = LoggerFactory.getLogger("axiz.errors");

  private final ProblemFactory factory;

  public ProblemResponder(ProblemFactory factory) {
    this.factory = Objects.requireNonNull(factory);
  }

  /** Builds the problem and logs the failure. */
  public Problem problem(
      Throwable failure, Locale locale, @Nullable String path, @Nullable String correlationId) {
    AxizException axiz = factory.toAxizException(failure);
    Problem problem =
        factory.create(
            axiz, locale, path, correlationId != null ? correlationId : MDC.get("correlationId"));
    log(axiz, problem, path);
    return problem;
  }

  public String json(Problem problem) {
    return ProblemJson.write(problem);
  }

  private static void log(AxizException axiz, Problem problem, @Nullable String path) {
    ErrorCategory category = axiz.category();
    if (problem.status() >= 500) {
      Throwable cause = axiz.getCause() != null ? axiz.getCause() : axiz;
      LOG.error(
          "status={} code={} path={} error={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage(),
          cause);
    } else if (axiz.type() == ErrorType.SECURITY || category == ErrorCategory.CONFLICT) {
      LOG.warn(
          "status={} code={} path={} error={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage());
    } else {
      LOG.debug(
          "status={} code={} path={} error={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage());
    }
  }
}
