package pe.axiz.payments.process.adapter.out.bankingcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.money.Money;
import pe.axiz.payments.process.adapter.out.bankingcore.model.Movement;
import pe.axiz.payments.process.adapter.out.bankingcore.model.MovementRequest;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.PaymentNetworkGatewayClient;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.TransfersAdapter;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.Transfer;
import pe.axiz.payments.process.adapter.out.paymentnetworkgateway.model.TransferRequest;
import pe.axiz.payments.process.domain.PaymentInstruction;

/** Lo que los movimientos de dinero de la saga envían a banking-core y lo que leen de vuelta. */
class BankingCoreAdaptersTest {

  /** Cliente que registra las solicitudes y responde lo que fija la prueba. */
  static final class Recording implements BankingCoreClient, PaymentNetworkGatewayClient {
    final List<Object> requests = new ArrayList<>();
    Movement movement = new Movement("MV-1", "POSTED", "OP-1");
    Transfer transfer = new Transfer("NET-1", "SETTLED", "OP-1");

    @Override
    public Movement debitAccount(String idempotencyKey, MovementRequest body) {
      requests.add(idempotencyKey);
      requests.add(body);
      return movement;
    }

    @Override
    public Movement reverseDebit(String movementId, String idempotencyKey) {
      requests.add("reverse " + movementId + " " + idempotencyKey);
      return movement;
    }

    @Override
    public Movement creditAccount(String idempotencyKey, MovementRequest body) {
      throw new UnsupportedOperationException("the saga never credits");
    }

    @Override
    public Transfer sendTransfer(String idempotencyKey, TransferRequest body) {
      requests.add(idempotencyKey);
      requests.add(body);
      return transfer;
    }
  }

  final Recording client = new Recording();
  final PaymentInstruction instruction =
      new PaymentInstruction(
          "O-1",
          "OP-1",
          "1910012345678",
          "00219100123456789053",
          Money.of("150.75", "PEN"),
          "C-1001");

  @Test
  void aDebitCarriesTheHolderAndReturnsTheMovement() {
    String movement = new AccountsAdapter(client).debit(instruction, "pp-O-1-debit");

    assertThat(movement).isEqualTo("MV-1");
    assertThat(client.requests)
        .containsExactly(
            "pp-O-1-debit",
            new MovementRequest(
                "1910012345678", new BigDecimal("150.75"), "PEN", "OP-1", "C-1001"));

    new AccountsAdapter(client).reverse("MV-1", "pp-O-1-reverse");
    assertThat(client.requests).contains("reverse MV-1 pp-O-1-reverse");
  }

  @Test
  void aTransferGoesToTheCreditorAccount() {
    String transfer = new TransfersAdapter(client).send(instruction, "pp-O-1-transfer");

    assertThat(transfer).isEqualTo("NET-1");
    assertThat(client.requests)
        .containsExactly(
            "pp-O-1-transfer",
            new TransferRequest("00219100123456789053", new BigDecimal("150.75"), "PEN", "OP-1"));
  }

  @Test
  void answersWithoutIdAreErrors() {
    client.movement = new Movement(null, "POSTED", "OP-1");
    client.transfer = new Transfer(null, "SETTLED", "OP-1");

    assertThatThrownBy(() -> new AccountsAdapter(client).debit(instruction, "k"))
        .hasMessageContaining("movement id");
    assertThatThrownBy(() -> new TransfersAdapter(client).send(instruction, "k"))
        .hasMessageContaining("transfer without id");
  }
}
