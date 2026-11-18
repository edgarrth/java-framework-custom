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
 * REST adapter of the {@code movements} operations of the contract (src/main/openapi). The
 * Idempotency-Key travels to the core, which de-duplicates movements by it. Only the payment
 * process moves money: customer tokens never reach these operations, even with a valid token.
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

  /** Holds and debits funds of an account in the core (POST /v1/debits). */
  @PostMapping("/v1/debits")
  public ResponseEntity<Movement> debitAccount(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody MovementRequest body) {
    var movement =
        debitAccountUseCase.execute(
            new DebitAccountUseCase.Input(RestMapper.order(body), idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(movement));
  }

  /** Reverses a debit (saga compensation) (POST /v1/debits/{movementId}/reversal). */
  @PostMapping("/v1/debits/{movementId}/reversal")
  public ResponseEntity<Movement> reverseDebit(
      @PathVariable("movementId") String movementId,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    var movement =
        reverseDebitUseCase.execute(new ReverseDebitUseCase.Input(movementId, idempotencyKey));
    return ResponseEntity.status(201).body(RestMapper.toApi(movement));
  }

  /** Credits an account of the core (SOAP service of the core) (POST /v1/credits). */
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
