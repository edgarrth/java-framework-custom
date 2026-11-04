package pe.axiz.payments.ux.composition;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.NotFoundException;

/**
 * Anti-corruption for errors: business codes of downstream services become channel errors; a 404
 * becomes a channel "not found"; anything else stays a dependency error (502/503/504).
 */
final class DownstreamErrors {

  private static final Map<String, ErrorDefinition> BUSINESS =
      Map.of(
          "PAYMENTS-PAYMENTSBUSINESS-0001", ChannelErrors.LIMIT_EXCEEDED,
          "PAYMENTS-PAYMENTSBUSINESS-0002", ChannelErrors.INVALID_BENEFICIARY,
          "PAYMENTS-PAYMENTSBUSINESS-0004", ChannelErrors.CURRENCY_NOT_AVAILABLE,
          "PAYMENTS-PAYMENTSBUSINESS-0005", ChannelErrors.DUPLICATE_PAYMENT);

  private DownstreamErrors() {}

  /** Maps a downstream failure; {@code resource} names what a 404 refers to. */
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
