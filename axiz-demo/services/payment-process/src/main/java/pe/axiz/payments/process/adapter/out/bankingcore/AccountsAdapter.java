package pe.axiz.payments.process.adapter.out.bankingcore;

import java.util.Objects;
import org.springframework.stereotype.Component;
import pe.axiz.payments.process.adapter.out.bankingcore.model.Movement;
import pe.axiz.payments.process.adapter.out.bankingcore.model.MovementRequest;
import pe.axiz.payments.process.application.port.out.Accounts;
import pe.axiz.payments.process.domain.PaymentInstruction;

/** Debits and reversals through core-integration (HTTP service group {@code banking-core}). */
@Component
public class AccountsAdapter implements Accounts {

  private final BankingCoreClient client;

  public AccountsAdapter(BankingCoreClient client) {
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
