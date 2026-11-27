package pe.axiz.spring.logging;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorType;

/**
 * Distingue los rechazos de negocio esperados (registrados en WARN) de los fallos (registrados en
 * ERROR).
 */
public interface FailureKind {

  boolean isExpected(Throwable error);

  @Nullable String code(Throwable error);

  /** Los errores de negocio, de validación y de seguridad son esperados; el resto son fallos. */
  static FailureKind axiz() {
    return new FailureKind() {
      @Override
      public boolean isExpected(Throwable error) {
        return error instanceof AxizException a
            && (a.type() == ErrorType.BUSINESS
                || a.type() == ErrorType.VALIDATION
                || a.type() == ErrorType.SECURITY);
      }

      @Override
      public @Nullable String code(Throwable error) {
        return error instanceof AxizException a ? a.code().value() : null;
      }
    };
  }
}
