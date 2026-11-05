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
import pe.axiz.payments.ux.api.model.PaymentRequest;
import pe.axiz.payments.ux.api.model.PaymentView;
import pe.axiz.payments.ux.composition.PaymentsComposition;
import reactor.core.publisher.Mono;

/**
 * REST adapter of the {@code payments} operations of the contract (src/main/openapi). Requests with
 * a repeated Idempotency-Key replay the first response (Redis store shared by replicas).
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_payments:channel')")
public class PaymentsController {

  private final PaymentsComposition composition;

  public PaymentsController(PaymentsComposition composition) {
    this.composition = composition;
  }

  /** Pays a frequent beneficiary (POST /v1/payments). */
  @Idempotent
  @PostMapping("/v1/payments")
  public Mono<ResponseEntity<PaymentView>> createPayment(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PaymentRequest body) {
    return composition
        .createPayment(idempotencyKey, body)
        .map(result -> ResponseEntity.status(201).body(result));
  }

  /** A payment with its beneficiary (GET /v1/payments/{paymentId}). */
  @GetMapping("/v1/payments/{paymentId}")
  public Mono<ResponseEntity<PaymentView>> getPayment(@PathVariable("paymentId") String paymentId) {
    return composition.getPayment(paymentId).map(result -> ResponseEntity.status(200).body(result));
  }
}
