package pe.axiz.payments.history.adapter.in.rest;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.extension.cqrs.spring.ReactiveQueryBus;
import pe.axiz.payments.history.adapter.in.rest.model.PaymentHistoryItem;
import pe.axiz.payments.history.adapter.in.rest.model.PaymentHistoryPage;
import pe.axiz.payments.history.application.query.GetPaymentQuery;
import pe.axiz.payments.history.application.query.ListCustomerPaymentsQuery;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.web.Paging;
import reactor.core.publisher.Mono;

/**
 * Adaptador REST de las operaciones {@code payment-history} (src/main/openapi/openapi.yaml):
 * consultas al bus reactivo y el dominio sale mediante {@link PaymentHistoryApiMapper}. Aquí nada
 * bloquea: la lectura va del event loop a Aurora sobre R2DBC y regresa.
 */
@RestController
@PreAuthorize("hasAnyAuthority('SCOPE_payments:channel', 'SCOPE_payments:read')")
public class PaymentHistoryController {

  private final ReactiveQueryBus queryBus;

  public PaymentHistoryController(ReactiveQueryBus queryBus) {
    this.queryBus = queryBus;
  }

  /**
   * Pagos de un cliente, con el cambio más reciente primero (GET
   * /v1/customers/{customerId}/payments).
   */
  @GetMapping("/v1/customers/{customerId}/payments")
  public Mono<ResponseEntity<PaymentHistoryPage>> listCustomerPayments(
      @PathVariable("customerId") String customerId,
      @RequestParam(name = "status", required = false) @Nullable String status,
      @Paging PageRequest page) {
    PaymentStatus filter = status == null ? null : status(status);
    return queryBus
        .ask(new ListCustomerPaymentsQuery(customerId, filter, page))
        .map(result -> ResponseEntity.ok(PaymentHistoryApiMapper.toApi(result)));
  }

  /** Obtiene el estado proyectado de un pago (GET /v1/payments/{paymentId}). */
  @GetMapping("/v1/payments/{paymentId}")
  public Mono<ResponseEntity<PaymentHistoryItem>> getPayment(
      @PathVariable("paymentId") String paymentId) {
    return queryBus
        .ask(new GetPaymentQuery(paymentId))
        .map(result -> ResponseEntity.ok(PaymentHistoryApiMapper.toApi(result)));
  }

  private static PaymentStatus status(String value) {
    try {
      return PaymentStatus.valueOf(value);
    } catch (IllegalArgumentException _) {
      throw new ValidationException(
          List.of(
              FieldViolation.of(
                  "status", "Enum", "must be one of " + List.of(PaymentStatus.values()))));
    }
  }
}
