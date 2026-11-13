package pe.axiz.spring.errors;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * failed dependencies (timeout, 5xx, no response) and denied access at WARN, conflicts at INFO and
 * other client errors at DEBUG, without it.
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
    if (problem.status() >= 500 && axiz.type() == ErrorType.EXTERNAL) {
      // the fault is in the dependency, not in this code: its stack trace says nothing new. The
      // client already logged each attempt (axiz.http.client) and the trace shows the failed call
      Throwable cause = axiz.getCause();
      LOG.warn(
          "status={} code={} path={} error={} cause={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage(),
          cause != null ? summary(cause) : "-");
    } else if (problem.status() >= 500) {
      Throwable cause = axiz.getCause() != null ? axiz.getCause() : axiz;
      LOG.error(
          "status={} code={} path={} error={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage(),
          cause);
    } else if (axiz.type() == ErrorType.SECURITY) {
      // denied access stays visible: repeated denials are how probing shows up
      LOG.warn(
          "status={} code={} path={} error={}",
          problem.status(),
          problem.code(),
          path,
          axiz.getMessage());
    } else if (category == ErrorCategory.CONFLICT) {
      // a conflict (duplicate, concurrent change) is an expected business answer, not a fault
      LOG.info(
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

  // type and message of the innermost cause (the transport failure), without the stack trace
  private static String summary(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() != null
        ? root.getClass().getSimpleName() + ": " + root.getMessage()
        : root.getClass().getSimpleName();
  }
}
