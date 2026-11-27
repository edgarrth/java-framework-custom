package pe.axiz.payments.ux.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.payments.ux.composition.PaymentsComposition;
import pe.axiz.payments.ux.composition.model.PaymentRequest;
import pe.axiz.payments.ux.composition.model.PaymentView;
import reactor.core.publisher.Mono;

/**
 * Adaptador REST de las operaciones {@code payments} del contrato (src/main/openapi). Las
 * solicitudes con un Idempotency-Key repetido reproducen la primera respuesta (almacén Redis
 * compartido por las réplicas).
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_payments:channel')")
public class PaymentsController {

  private final PaymentsComposition composition;

  public PaymentsController(PaymentsComposition composition) {
    this.composition = composition;
  }

  /** Paga a un beneficiario frecuente (POST /v1/payments). */
  @Idempotent
  @PostMapping("/v1/payments")
  public Mono<ResponseEntity<PaymentView>> createPayment(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PaymentRequest body) {
    return composition
        .createPayment(idempotencyKey, body)
        .map(result -> ResponseEntity.status(201).body(result));
  }

  /** Un pago con su beneficiario (GET /v1/payments/{paymentId}). */
  @GetMapping("/v1/payments/{paymentId}")
  public Mono<ResponseEntity<PaymentView>> getPayment(@PathVariable("paymentId") String paymentId) {
    return composition.getPayment(paymentId).map(result -> ResponseEntity.status(200).body(result));
  }
}
