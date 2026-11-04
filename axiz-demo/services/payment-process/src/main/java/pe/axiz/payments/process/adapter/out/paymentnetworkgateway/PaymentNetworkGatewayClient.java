package pe.axiz.payments.process.adapter.out.paymentnetworkgateway;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.Transfer;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.TransferRequest;

/** Calls the payment network gateway through the configured HTTP service group. */
@HttpExchange
public interface PaymentNetworkGatewayClient {
  @PostExchange("/v1/transfers")
  Transfer sendTransfer(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody TransferRequest body);
}
