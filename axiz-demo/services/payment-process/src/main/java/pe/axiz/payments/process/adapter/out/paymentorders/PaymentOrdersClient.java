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
 * Cliente de la API Payment orders (1.0.0), grupo de servicios HTTP {@code payment-orders}: la URL
 * base, los timeouts, los reintentos, el circuit breaker y la autenticación provienen de {@code
 * axiz.http-client.clients.payment-orders.*}.
 */
@HttpExchange
public interface PaymentOrdersClient {

  /** Registra una orden de pago (POST /v1/payment-orders). */
  @PostExchange("/v1/payment-orders")
  PaymentOrder createPaymentOrder(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody PaymentOrderRequest body);

  /** Órdenes de pago de un cliente (GET /v1/payment-orders). */
  @GetExchange("/v1/payment-orders")
  PaymentOrderPage listPaymentOrders(
      @RequestParam(name = "customerId", required = true) String customerId,
      @RequestParam(name = "page", required = false) @Nullable Integer page,
      @RequestParam(name = "size", required = false) @Nullable Integer size);

  /** Obtiene una orden de pago (GET /v1/payment-orders/{orderId}). */
  @GetExchange("/v1/payment-orders/{orderId}")
  PaymentOrder getPaymentOrder(@PathVariable("orderId") String orderId);

  /**
   * Mueve la orden a su siguiente estado (lo usa el proceso de pagos) (POST
   * /v1/payment-orders/{orderId}/status).
   */
  @PostExchange("/v1/payment-orders/{orderId}/status")
  PaymentOrder changePaymentOrderStatus(
      @PathVariable("orderId") String orderId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody StatusChange body);
}
