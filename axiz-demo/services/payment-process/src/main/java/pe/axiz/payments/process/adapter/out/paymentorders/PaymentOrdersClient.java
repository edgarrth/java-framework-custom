package pe.axiz.payments.process.adapter.out.paymentorders;

import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrder;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrderPage;
import pe.axiz.payments.process.adapter.out.paymentorders.model.PaymentOrderRequest;
import pe.axiz.payments.process.adapter.out.paymentorders.model.StatusChange;

/**
 * Client of the Payment orders API (1.0.0), HTTP service group {@code payment-orders}: base URL,
 * timeouts, retries, circuit breaker and authentication come from {@code
 * axiz.http-client.clients.payment-orders.*}.
 */
@HttpExchange
public interface PaymentOrdersClient {

  /** Registers a payment order (POST /v1/payment-orders). */
  @PostExchange("/v1/payment-orders")
  PaymentOrder createPaymentOrder(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody PaymentOrderRequest body);

  /** Payment orders of a customer (GET /v1/payment-orders). */
  @GetExchange("/v1/payment-orders")
  PaymentOrderPage listPaymentOrders(
      @RequestParam(name = "customerId", required = true) String customerId,
      @RequestParam(name = "page", required = false) @Nullable Integer page,
      @RequestParam(name = "size", required = false) @Nullable Integer size);

  /** Gets a payment order (GET /v1/payment-orders/{orderId}). */
  @GetExchange("/v1/payment-orders/{orderId}")
  PaymentOrder getPaymentOrder(@PathVariable("orderId") String orderId);

  /**
   * Moves the order to its next status (used by the payment process) (POST
   * /v1/payment-orders/{orderId}/status).
   */
  @PostExchange("/v1/payment-orders/{orderId}/status")
  PaymentOrder changePaymentOrderStatus(
      @PathVariable("orderId") String orderId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody StatusChange body);
}
