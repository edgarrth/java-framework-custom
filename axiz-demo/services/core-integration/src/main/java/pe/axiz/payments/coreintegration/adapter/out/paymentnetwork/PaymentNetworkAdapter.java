package pe.axiz.payments.coreintegration.adapter.out.paymentnetwork;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.payments.coreintegration.adapter.out.paymentnetwork.model.NetworkReceipt;
import pe.axiz.payments.coreintegration.adapter.out.paymentnetwork.model.NetworkTransfer;
import pe.axiz.payments.coreintegration.application.port.out.PaymentNetwork;
import pe.axiz.payments.coreintegration.domain.IntegrationErrors;
import pe.axiz.payments.coreintegration.domain.Transfer;
import pe.axiz.payments.coreintegration.domain.TransferOrder;
import pe.axiz.payments.coreintegration.domain.TransferStatus;

/**
 * Adapter of the inter-bank network (API key from the secret store, HTTP service group {@code
 * payment-network}). Network rejection codes become {@code TRANSFER_REJECTED} with a stable reason.
 */
@Component
public class PaymentNetworkAdapter implements PaymentNetwork {

  static final Map<String, String> REJECTIONS =
      Map.of("NET-R01", "invalid-account", "NET-R02", "account-closed", "NET-R09", "limit");
  static final Set<String> RESULTS = Set.of("SETTLED", "PENDING");

  private final PaymentNetworkClient client;

  public PaymentNetworkAdapter(PaymentNetworkClient client) {
    this.client = client;
  }

  @Override
  public Transfer send(TransferOrder order, String idempotencyKey) {
    NetworkReceipt receipt;
    try {
      receipt =
          client.submitTransfer(
              idempotencyKey,
              new NetworkTransfer(
                  order.creditorCci(),
                  order.amount().amount(),
                  order.amount().currencyCode(),
                  order.reference()));
    } catch (ExternalServiceException e) {
      String reason = e.detail().code() == null ? null : REJECTIONS.get(e.detail().code());
      if (reason != null) {
        throw new BusinessException(
            IntegrationErrors.TRANSFER_REJECTED,
            "network " + e.detail().code() + " for " + order.reference(),
            Map.of("reason", reason));
      }
      throw e;
    }
    String result = Objects.requireNonNullElse(receipt.result(), "PENDING");
    return new Transfer(
        Objects.requireNonNull(receipt.id(), "network receipt without id"),
        RESULTS.contains(result) ? TransferStatus.valueOf(result) : TransferStatus.PENDING,
        order.reference());
  }
}
