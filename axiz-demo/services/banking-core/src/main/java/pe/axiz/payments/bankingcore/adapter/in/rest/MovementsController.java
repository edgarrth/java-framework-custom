package pe.axiz.payments.bankingcore.adapter.in.rest;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.payments.bankingcore.adapter.in.rest.model.Movement;
import pe.axiz.payments.bankingcore.adapter.in.rest.model.MovementRequest;
import pe.axiz.payments.bankingcore.application.usecase.CreditAccountUseCase;
import pe.axiz.payments.bankingcore.application.usecase.DebitAccountUseCase;
import pe.axiz.payments.bankingcore.application.usecase.ReverseDebitUseCase;

/**
 * Adaptador REST de las operaciones {@code movements} del contrato (src/main/openapi). El
 * Idempotency-Key viaja al core, que deduplica los movimientos por esa clave. Solo el proceso de
 * pagos mueve dinero: los tokens de clientes nunca llegan a estas operaciones, ni siquiera con un
 * token válido.
 */
@RestController
@PreAuthorize("hasAuthority('SCOPE_payments:process')")
public class MovementsController {

  private final DebitAccountUseCase debitAccountUseCase;
  private final ReverseDebitUseCase reverseDebitUseCase;
  private final CreditAccountUseCase creditAccountUseCase;

  public MovementsController(
      DebitAccountUseCase debitAccountUseCase,
      ReverseDebitUseCase reverseDebitUseCase,
      CreditAccountUseCase creditAccountUseCase) {
    this.debitAccountUseCase = debitAccountUseCase;
    this.reverseDebitUseCase = reverseDebitUseCase;
    this.creditAccountUseCase = creditAccountUseCase;
  }

  /** Retiene y debita fondos de una cuenta en el core (POST /v1/debits). */
  @PostMapping("/v1/debits")
  public ResponseEntity<Movement> debitAccount(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody MovementRequest body) {
    var movement =
        debitAccountUseCase.execute(
            new DebitAccountUseCase.Input(RestMapper.order(body), idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(movement));
  }

  /** Revierte un débito (compensación de la saga) (POST /v1/debits/{movementId}/reversal). */
  @PostMapping("/v1/debits/{movementId}/reversal")
  public ResponseEntity<Movement> reverseDebit(
      @PathVariable("movementId") String movementId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    var movement =
        reverseDebitUseCase.execute(new ReverseDebitUseCase.Input(movementId, idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(movement));
  }

  /** Abona a una cuenta del core (servicio SOAP del core) (POST /v1/credits). */
  @PostMapping("/v1/credits")
  public ResponseEntity<Movement> creditAccount(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody MovementRequest body) {
    var movement =
        creditAccountUseCase.execute(
            new CreditAccountUseCase.Input(RestMapper.order(body), idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(movement));
  }
}
