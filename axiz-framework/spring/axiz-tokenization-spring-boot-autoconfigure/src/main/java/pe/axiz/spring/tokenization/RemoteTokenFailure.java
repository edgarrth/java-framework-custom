package pe.axiz.spring.tokenization;

import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.TokenErrors;

/**
 * Solo se conserva el catálogo cerrado de errores de tokens; una pérdida de transporte no puede
 * demostrar que una mutación hizo rollback.
 */
final class RemoteTokenFailure {
  private RemoteTokenFailure() {}

  static BusinessException safe(Throwable failure, boolean mutation) {
    if (failure instanceof AxizException axiz && axiz.code().value().startsWith("AXZ-TOKEN-"))
      return TokenErrors.sanitize(failure);
    return TokenErrors.failure(mutation ? TokenErrors.UNCERTAIN : TokenErrors.UNAVAILABLE);
  }
}
