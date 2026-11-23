package pe.axiz.payments.process.adapter.out.paymentnetworkgateway;

import java.util.Objects;
import org.springframework.stereotype.Component;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.Transfer;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.TransferRequest;
import pe.axiz.payments.process.application.port.out.Transfers;
import pe.axiz.payments.process.domain.PaymentInstruction;

/**
 * Transferencias interbancarias mediante payment-network-gateway. La clave de idempotencia hace que
 * un reintento tras un timeout devuelva el primer resultado en lugar de enviar el dinero dos veces.
 */
@Component
public class TransfersAdapter implements Transfers {

  private final PaymentNetworkGatewayClient client;

  public TransfersAdapter(PaymentNetworkGatewayClient client) {
    this.client = client;
  }

  @Override
  public String send(PaymentInstruction instruction, String idempotencyKey) {
    Transfer t =
        client.sendTransfer(
            idempotencyKey,
            new TransferRequest(
                instruction.creditorCci(),
                instruction.amount().amount(),
                instruction.amount().currencyCode(),
                instruction.operationNumber()));
    return Objects.requireNonNull(t.transferId(), "transfer without id");
  }
}
