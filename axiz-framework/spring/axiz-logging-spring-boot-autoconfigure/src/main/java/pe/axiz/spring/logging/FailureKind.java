package pe.axiz.spring.logging;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.ErrorType;

/** Tells expected business rejections (logged at WARN) from failures (logged at ERROR). */
public interface FailureKind {

  boolean isExpected(Throwable error);

  @Nullable String code(Throwable error);

  /** Business, validation and security errors are expected; the rest are failures. */
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
