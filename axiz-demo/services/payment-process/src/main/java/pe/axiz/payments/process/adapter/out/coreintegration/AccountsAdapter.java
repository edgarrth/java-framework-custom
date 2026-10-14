package pe.axiz.payments.process.adapter.out.coreintegration;

import java.util.Objects;
import org.springframework.stereotype.Component;
import pe.axiz.payments.process.adapter.out.coreintegration.model.Movement;
import pe.axiz.payments.process.adapter.out.coreintegration.model.MovementRequest;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.domain.PaymentInstruction;

/** Debits and reversals through core-integration (HTTP service group {@code core-integration}). */
@Component
public class AccountsAdapter implements Accounts {

  private final CoreIntegrationClient client;

  public AccountsAdapter(CoreIntegrationClient client) {
    this.client = client;
  }

  @Override
  public String debit(PaymentInstruction instruction, String idempotencyKey) {
    Movement m =
        client.debitAccount(
            idempotencyKey,
            new MovementRequest(
                instruction.debtorAccount(),
                instruction.amount().amount(),
                instruction.amount().currencyCode(),
                instruction.operationNumber(),
                instruction.customerId()));
    return Objects.requireNonNull(m.movementId(), "debit without movement id");
  }

  @Override
  public void reverse(String movementId, String idempotencyKey) {
    client.reverseDebit(movementId, idempotencyKey);
  }
}
