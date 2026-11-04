package pe.axiz.payments.bankingcore.adapter.out.corebanking;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreDebit;
import pe.axiz.payments.bankingcore.adapter.out.corebanking.model.CoreMovement;

/**
 * Client of the Core banking (simulated) API (2.3.0), HTTP service group {@code core-banking}: base
 * URL, timeouts, retries, circuit breaker and authentication come from {@code
 * axiz.http-client.clients.core-banking.*}.
 */
@HttpExchange
public interface CoreBankingClient {

  /** Holds and posts a debit (POST /core/v2/accounts/{account}/debits). */
  @PostExchange("/core/v2/accounts/{account}/debits")
  CoreMovement postDebit(
      @PathVariable("account") String account,
      @RequestHeader(name = "X-Request-Ref", required = true) String xRequestRef,
      @RequestBody CoreDebit body);

  /** Reverses a posted movement (POST /core/v2/movements/{mvId}/reversal). */
  @PostExchange("/core/v2/movements/{mvId}/reversal")
  CoreMovement postReversal(
      @PathVariable("mvId") String mvId,
      @RequestHeader(name = "X-Request-Ref", required = true) String xRequestRef);
}
