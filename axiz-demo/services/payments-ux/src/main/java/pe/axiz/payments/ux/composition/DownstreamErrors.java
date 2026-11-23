package pe.axiz.payments.ux.composition;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.NotFoundException;

/**
 * Anticorrupción para errores: los códigos de negocio de los servicios aguas abajo se convierten en
 * errores del canal; un 404 se convierte en un "no encontrado" del canal; cualquier otro caso sigue
 * siendo un error de dependencia (502/503/504).
 */
final class DownstreamErrors {

  private static final Map<String, ErrorDefinition> BUSINESS =
      Map.of(
          "PAYMENTS-PAYMENTORDERS-0001", ChannelErrors.LIMIT_EXCEEDED,
          "PAYMENTS-PAYMENTORDERS-0002", ChannelErrors.INVALID_BENEFICIARY,
          "PAYMENTS-PAYMENTORDERS-0004", ChannelErrors.CURRENCY_NOT_AVAILABLE,
          "PAYMENTS-PAYMENTORDERS-0005", ChannelErrors.DUPLICATE_PAYMENT);

  private DownstreamErrors() {}

  /**
   * Convierte una falla aguas abajo; {@code resource} nombra aquello a lo que se refiere un 404.
   */
  static Throwable translate(Throwable error, String resource, String id) {
    if (!(error instanceof ExternalServiceException e)) {
      return error;
    }
    if (e.detail().status() == 404) {
      return new NotFoundException(resource, id);
    }
    @Nullable String code = e.detail().code();
    ErrorDefinition channel = code == null ? null : BUSINESS.get(code);
    if (channel != null) {
      return new BusinessException(channel, "downstream " + code, Map.of());
    }
    return error;
  }
}
