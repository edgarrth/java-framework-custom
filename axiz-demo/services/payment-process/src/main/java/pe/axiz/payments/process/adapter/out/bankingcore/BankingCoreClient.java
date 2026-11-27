package pe.axiz.payments.process.adapter.out.bankingcore;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.process.adapter.out.bankingcore.model.Movement;
import pe.axiz.payments.process.adapter.out.bankingcore.model.MovementRequest;

/**
 * Cliente de la API Banking Core (1.0.0), grupo de servicios HTTP {@code banking-core}: la URL
 * base, los timeouts, los reintentos, el circuit breaker y la autenticación provienen de {@code
 * axiz.http-client.clients.banking-core.*}.
 */
@HttpExchange
public interface BankingCoreClient {

  /** Retiene y debita fondos de una cuenta en el core (POST /v1/debits). */
  @PostExchange("/v1/debits")
  Movement debitAccount(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody MovementRequest body);

  /** Revierte un débito (compensación de la saga) (POST /v1/debits/{movementId}/reversal). */
  @PostExchange("/v1/debits/{movementId}/reversal")
  Movement reverseDebit(
      @PathVariable("movementId") String movementId,
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey);

  /** Abona a una cuenta del core (servicio SOAP del core) (POST /v1/credits). */
  @PostExchange("/v1/credits")
  Movement creditAccount(
      @RequestHeader(name = "Idempotency-Key", required = true) String idempotencyKey,
      @RequestBody MovementRequest body);
}
