package pe.axiz.spring.tokenization;

import pe.axiz.core.errors.AxizException;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.TokenErrors;

/**
 * Preserve only the closed token error catalog; transport loss cannot prove a mutation rolled back.
 */
final class RemoteTokenFailure {
  private RemoteTokenFailure() {}

  static BusinessException safe(Throwable failure, boolean mutation) {
    if (failure instanceof AxizException axiz && axiz.code().value().startsWith("AXZ-TOKEN-"))
      return TokenErrors.sanitize(failure);
    return TokenErrors.failure(mutation ? TokenErrors.UNCERTAIN : TokenErrors.UNAVAILABLE);
  }
}
