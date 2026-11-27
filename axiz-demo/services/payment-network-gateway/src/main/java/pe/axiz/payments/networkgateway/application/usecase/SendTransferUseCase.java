package pe.axiz.payments.networkgateway.application.usecase;

import java.util.Locale;
import java.util.Map;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.payments.networkgateway.application.port.out.PaymentNetwork;
import pe.axiz.payments.networkgateway.domain.GatewayErrors;
import pe.axiz.payments.networkgateway.domain.Transfer;
import pe.axiz.payments.networkgateway.domain.TransferOrder;
import pe.axiz.structure.hexagonal.annotation.UseCase;

/**
 * Envía una transferencia interbancaria. Los dígitos de control del CCI se verifican aquí, de modo
 * que las cuentas claramente erróneas nunca cuestan una llamada a la red.
 */
@UseCase
public class SendTransferUseCase
    implements pe.axiz.structure.hexagonal.UseCase<SendTransferUseCase.Input, Transfer> {

  /**
   * Entrada del caso de uso.
   *
   * @param order transferencia que se envía
   * @param idempotencyKey clave del invocador, reenviada a la red
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
          GatewayErrors.TRANSFER_REJECTED, "invalid cci", Map.of("reason", "invalid-account"));
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
