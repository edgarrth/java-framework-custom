package pe.axiz.payments.coreintegration.application.usecase;

import java.util.Locale;
import java.util.Map;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.framework.hexagonal.annotation.UseCase;
import pe.axiz.payments.coreintegration.application.port.out.PaymentNetwork;
import pe.axiz.payments.coreintegration.domain.IntegrationErrors;
import pe.axiz.payments.coreintegration.domain.Transfer;
import pe.axiz.payments.coreintegration.domain.TransferOrder;

/**
 * Sends an interbank transfer. The CCI check digits are verified here, so obviously wrong accounts
 * never cost a network call.
 */
@UseCase
public class SendTransferUseCase
    implements pe.axiz.framework.hexagonal.UseCase<SendTransferUseCase.Input, Transfer> {

  /**
   * Input of the use case.
   *
   * @param order transfer to send
   * @param idempotencyKey caller key, forwarded to the network
   */
  public record Input(TransferOrder order, String idempotencyKey) {}

  private final PaymentNetwork network;
  private final IdentifierRegistry identifiers;
  private final EventMetrics metrics;

  public SendTransferUseCase(
      PaymentNetwork network, IdentifierRegistry identifiers, EventMetrics metrics) {
    this.network = network;
    this.identifiers = identifiers;
    this.metrics = metrics;
  }

  @Override
  @Auditable(action = "transfer.send", resource = "transfer", captureArguments = false)
  public Transfer execute(Input input) {
    if (!identifiers.isValid("cci-strict", input.order().creditorCci())) {
      metrics.record(
          MetricEvent.of("network.transfer").outcome(Outcome.REJECTED).reason("invalid-cci"));
      throw new BusinessException(
          IntegrationErrors.TRANSFER_REJECTED, "invalid cci", Map.of("reason", "invalid-account"));
    }
    Transfer transfer = network.send(input.order(), input.idempotencyKey());
    metrics.record(
        MetricEvent.of("network.transfer")
            .amount(input.order().amount())
            .stage(transfer.status().name().toLowerCase(Locale.ROOT))
            .outcome(Outcome.SUCCESS));
    return transfer;
  }
}
