package pe.axiz.payments.coreintegration.adapter.out.corebanking;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.errors.TechnicalException;
import pe.axiz.payments.coreintegration.adapter.out.corebanking.model.CoreDebit;
import pe.axiz.payments.coreintegration.adapter.out.corebanking.model.CoreMovement;
import pe.axiz.payments.coreintegration.adapter.out.soap.CoreCreditGateway;
import pe.axiz.payments.coreintegration.application.port.out.CoreAccounts;
import pe.axiz.payments.coreintegration.domain.IntegrationErrors;
import pe.axiz.payments.coreintegration.domain.Movement;
import pe.axiz.payments.coreintegration.domain.MovementOrder;
import pe.axiz.payments.coreintegration.domain.MovementStatus;

/**
 * Anti-corruption adapter of the core banking system: debits and reversals through its REST facade
 * (client credentials, HTTP service group {@code core-banking}), credits through its SOAP service.
 * Core codes and states never leave this class.
 */
@Component
public class CoreBankingAdapter implements CoreAccounts {

  /** Core error codes and what they mean for us. */
  static final String INSUFFICIENT_FUNDS = "CB-51";

  static final String ACCOUNT_BLOCKED = "CB-62";
  static final String ACCOUNT_CLOSED = "CB-63";
  static final String UNKNOWN_MOVEMENT = "CB-14";

  /** The account does not belong to the holder sent with the debit. */
  static final String NOT_ACCOUNT_HOLDER = "CB-17";

  private final CoreBankingClient client;
  private final CoreCreditGateway credits;

  public CoreBankingAdapter(CoreBankingClient client, CoreCreditGateway credits) {
    this.client = client;
    this.credits = credits;
  }

  @Override
  public Movement debit(MovementOrder order, String idempotencyKey) {
    try {
      CoreMovement m =
          client.postDebit(
              order.accountId(),
              idempotencyKey,
              new CoreDebit(
                  order.amount().amount().toPlainString(),
                  order.amount().currencyCode(),
                  order.reference(),
                  order.holderId()));
      return toMovement(m, order.reference());
    } catch (ExternalServiceException e) {
      throw translate(e, order.reference());
    }
  }

  @Override
  public Movement reverse(String movementId, String idempotencyKey) {
    try {
      return toMovement(client.postReversal(movementId, idempotencyKey), movementId);
    } catch (ExternalServiceException e) {
      if (e.detail().status() == 404 || UNKNOWN_MOVEMENT.equals(e.detail().code())) {
        throw new NotFoundException("movement", movementId);
      }
      throw translate(e, movementId);
    }
  }

  @Override
  public Movement credit(MovementOrder order, String idempotencyKey) {
    try {
      return credits.credit(order);
    } catch (ExternalServiceException e) {
      throw translate(e, order.reference());
    }
  }

  static Movement toMovement(CoreMovement m, String reference) {
    String id = Objects.requireNonNull(m.mvId(), "core movement without id");
    return new Movement(id, state(m.state()), reference);
  }

  /** Core states: {@code P} posted, {@code R} reversed. */
  static MovementStatus state(@Nullable String coreState) {
    return switch (coreState == null ? "" : coreState) {
      case "P" -> MovementStatus.POSTED;
      case "R" -> MovementStatus.REVERSED;
      default -> throw new TechnicalException("unknown core movement state " + coreState, null);
    };
  }

  /** Business rejections become our errors; anything else stays a dependency failure. */
  static RuntimeException translate(ExternalServiceException e, String reference) {
    String code = e.detail().code();
    if (INSUFFICIENT_FUNDS.equals(code)) {
      return new BusinessException(
          IntegrationErrors.INSUFFICIENT_FUNDS, "core " + code + " for " + reference, Map.of());
    }
    if (NOT_ACCOUNT_HOLDER.equals(code)) {
      return new BusinessException(
          IntegrationErrors.ACCOUNT_NOT_OWNED, "core " + code + " for " + reference, Map.of());
    }
    if (ACCOUNT_BLOCKED.equals(code) || ACCOUNT_CLOSED.equals(code)) {
      return new BusinessException(
          IntegrationErrors.ACCOUNT_NOT_OPERATIVE, "core " + code + " for " + reference, Map.of());
    }
    return e;
  }
}
