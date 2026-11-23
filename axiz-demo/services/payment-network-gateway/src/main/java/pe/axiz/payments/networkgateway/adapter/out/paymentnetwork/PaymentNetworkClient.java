package pe.axiz.payments.networkgateway.adapter.out.paymentnetwork;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.networkgateway.adapter.out.paymentnetwork.model.NetworkReceipt;
import pe.axiz.payments.networkgateway.adapter.out.paymentnetwork.model.NetworkTransfer;

/**
 * Cliente de la API de la red de pagos Clearing (simulada) (1.0.0), grupo de servicios HTTP {@code
 * payment-network}: la URL base, los timeouts, los reintentos, el circuit breaker y la
 * autenticación provienen de {@code axiz.http-client.clients.payment-network.*}.
 */
@HttpExchange
public interface PaymentNetworkClient {

  /** Envía una transferencia interbancaria inmediata (POST /api/transfers). */
  @PostExchange("/api/transfers")
  NetworkReceipt submitTransfer(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody NetworkTransfer body);
}
