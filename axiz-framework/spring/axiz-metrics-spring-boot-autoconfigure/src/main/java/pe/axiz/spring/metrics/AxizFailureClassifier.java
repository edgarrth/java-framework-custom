package pe.axiz.spring.metrics;

import java.util.Locale;
import java.util.Optional;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorType;
import pe.axiz.core.metrics.FailureClassifier;
import pe.axiz.core.metrics.Outcome;

/**
 * Business, validation and security errors are {@code rejected} with the catalog code as reason;
 * technical and external errors are {@code error} with their code.
 */
public class AxizFailureClassifier implements FailureClassifier {

  @Override
  public Optional<Classification> classify(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof AxizException a) {
        boolean rejected =
            a.type() == ErrorType.BUSINESS
                || a.type() == ErrorType.VALIDATION
                || a.type() == ErrorType.SECURITY;
        return Optional.of(
            new Classification(
                rejected ? Outcome.REJECTED : Outcome.ERROR,
                a.code().value().toLowerCase(Locale.ROOT)));
      }
    }
    return Optional.empty();
  }
}
