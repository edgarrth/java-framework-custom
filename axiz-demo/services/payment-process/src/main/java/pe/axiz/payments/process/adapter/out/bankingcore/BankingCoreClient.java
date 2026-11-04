package pe.axiz.payments.process.adapter.out.bankingcore;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.process.adapter.out.bankingcore.model.Movement;
import pe.axiz.payments.process.adapter.out.bankingcore.model.MovementRequest;

/**
 * Client of the Core integration API (1.0.0), HTTP service group {@code banking-core}: base URL,
 * timeouts, retries, circuit breaker and authentication come from {@code
 * axiz.http-client.clients.banking-core.*}.
 */
@HttpExchange
public interface BankingCoreClient {

  /** Holds and debits funds of an account in the core (POST /v1/debits). */
  @PostExchange("/v1/debits")
  Movement debitAccount(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody MovementRequest body);

  /** Reverses a debit (saga compensation) (POST /v1/debits/{movementId}/reversal). */
  @PostExchange("/v1/debits/{movementId}/reversal")
  Movement reverseDebit(
      @PathVariable("movementId") String movementId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey);

  /** Credits an account of the core (SOAP service of the core) (POST /v1/credits). */
  @PostExchange("/v1/credits")
  Movement creditAccount(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody MovementRequest body);
}
