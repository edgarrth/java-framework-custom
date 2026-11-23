package pe.axiz.payments.bankingcore.adapter.out.corebanking;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreDebit;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreMovement;

/**
 * Cliente de la API de Core banking (simulada) (2.3.0), grupo de servicios HTTP {@code
 * core-banking}: la URL base, los timeouts, los reintentos, el circuit breaker y la autenticación
 * provienen de {@code axiz.http-client.clients.core-banking.*}.
 */
@HttpExchange
public interface CoreBankingClient {

  /** Retiene y contabiliza un débito (POST /core/v2/accounts/{account}/debits). */
  @PostExchange("/core/v2/accounts/{account}/debits")
  CoreMovement postDebit(
      @PathVariable("account") String account,
      @RequestHeader(name = "X-Request-Ref", required = true) String xRequestRef,
      @RequestBody CoreDebit body);

  /** Revierte un movimiento contabilizado (POST /core/v2/movements/{mvId}/reversal). */
  @PostExchange("/core/v2/movements/{mvId}/reversal")
  CoreMovement postReversal(
      @PathVariable("mvId") String mvId,
      @RequestHeader(name = "X-Request-Ref", required = true) String xRequestRef);
}
