package pe.axiz.payments.coreintegration.adapter.out.paymentnetwork;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.coreintegration.adapter.out.paymentnetwork.model.NetworkReceipt;
import pe.axiz.payments.coreintegration.adapter.out.paymentnetwork.model.NetworkTransfer;

/**
 * Client of the Clearing payment network (simulated) API (1.0.0), HTTP service group {@code
 * payment-network}: base URL, timeouts, retries, circuit breaker and authentication come from
 * {@code axiz.http-client.clients.payment-network.*}.
 */
@HttpExchange
public interface PaymentNetworkClient {

  /** Submits an immediate inter-bank transfer (POST /api/transfers). */
  @PostExchange("/api/transfers")
  NetworkReceipt submitTransfer(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody NetworkTransfer body);
}
