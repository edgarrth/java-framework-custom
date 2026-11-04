package pe.axiz.payments.orders.adapter.in.rest;

import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.extension.cqrs.CommandBus;
import pe.axiz.extension.cqrs.QueryBus;
import pe.axiz.payments.orders.api.model.PaymentOrder;
import pe.axiz.payments.orders.api.model.PaymentOrderPage;
import pe.axiz.payments.orders.api.model.PaymentOrderRequest;
import pe.axiz.payments.orders.api.model.StatusChange;
import pe.axiz.payments.orders.application.command.ChangePaymentOrderStatusCommand;
import pe.axiz.payments.orders.application.command.CreatePaymentOrderCommand;
import pe.axiz.payments.orders.application.query.GetPaymentOrderQuery;
import pe.axiz.payments.orders.application.query.ListPaymentOrdersQuery;
import pe.axiz.payments.orders.domain.PaymentStatus;
import pe.axiz.spring.web.Paging;

/**
 * REST adapter of the {@code payment-orders} operations (src/main/openapi/openapi.yaml): API models
 * in, commands and queries to the buses, domain out through {@link PaymentOrderApiMapper}.
 */
@RestController
public class PaymentOrdersController {

  private final CommandBus commandBus;
  private final QueryBus queryBus;

  public PaymentOrdersController(CommandBus commandBus, QueryBus queryBus) {
    this.commandBus = commandBus;
    this.queryBus = queryBus;
  }

  /** Registers a payment order (POST /v1/payment-orders). */
  @PostMapping("/v1/payment-orders")
  public ResponseEntity<PaymentOrder> createPaymentOrder(
      @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
      @Valid @RequestBody PaymentOrderRequest body) {
    var order =
        commandBus.send(
            new CreatePaymentOrderCommand(
                body.getCustomerId(),
                body.getDebtorAccount(),
                body.getCreditorCci(),
                body.getAmount(),
                body.getCurrency(),
                body.getDescription(),
                idempotencyKey));
    return ResponseEntity.created(URI.create("/v1/payment-orders/" + order.id()))
        .body(PaymentOrderApiMapper.toApi(order));
  }

  /** Payment orders of a customer (GET /v1/payment-orders). */
  @GetMapping("/v1/payment-orders")
  public ResponseEntity<PaymentOrderPage> listPaymentOrders(
      @RequestParam(name = "customerId", required = true) String customerId,
      @Paging PageRequest page) {
    return ResponseEntity.ok(
        PaymentOrderApiMapper.toApi(queryBus.ask(new ListPaymentOrdersQuery(customerId, page))));
  }

  /** Gets a payment order (GET /v1/payment-orders/{orderId}). */
  @GetMapping("/v1/payment-orders/{orderId}")
  public ResponseEntity<PaymentOrder> getPaymentOrder(@PathVariable("orderId") String orderId) {
    return ResponseEntity.ok(
        PaymentOrderApiMapper.toApi(queryBus.ask(new GetPaymentOrderQuery(orderId))));
  }

  /** Moves the order to its next status (POST /v1/payment-orders/{orderId}/status). */
  @PostMapping("/v1/payment-orders/{orderId}/status")
  public ResponseEntity<PaymentOrder> changePaymentOrderStatus(
      @PathVariable("orderId") String orderId, @Valid @RequestBody StatusChange body) {
    var order =
        commandBus.send(
            new ChangePaymentOrderStatusCommand(
                orderId, PaymentStatus.valueOf(body.getStatus().getValue()), body.getReason()));
    return ResponseEntity.ok(PaymentOrderApiMapper.toApi(order));
  }
}
