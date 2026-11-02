package pe.axiz.payments.coreintegration.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.payments.coreintegration.api.model.Movement;
import pe.axiz.payments.coreintegration.api.model.MovementRequest;
import pe.axiz.payments.coreintegration.api.model.Transfer;
import pe.axiz.payments.coreintegration.api.model.TransferRequest;
import pe.axiz.payments.coreintegration.application.port.out.CoreAccounts;
import pe.axiz.payments.coreintegration.application.usecase.CreditAccountUseCase;
import pe.axiz.payments.coreintegration.application.usecase.DebitAccountUseCase;
import pe.axiz.payments.coreintegration.application.usecase.ReverseDebitUseCase;
import pe.axiz.payments.coreintegration.application.usecase.SendTransferUseCase;
import pe.axiz.payments.coreintegration.domain.MovementOrder;
import pe.axiz.payments.coreintegration.domain.MovementStatus;
import pe.axiz.payments.coreintegration.domain.TransferStatus;

/**
 * The REST adapters over the real use cases: the contract request becomes the domain order (with
 * the caller's Idempotency-Key), and the answer is a 201 with the contract model.
 */
class ControllersTest {

  /** Core that records the orders it receives. */
  static final class RecordingCore implements CoreAccounts {
    final List<String> calls = new ArrayList<>();
    final List<MovementOrder> orders = new ArrayList<>();

    @Override
    public pe.axiz.payments.coreintegration.domain.Movement debit(MovementOrder order, String key) {
      calls.add("debit " + key);
      orders.add(order);
      return new pe.axiz.payments.coreintegration.domain.Movement(
          "MV-D", MovementStatus.POSTED, order.reference());
    }

    @Override
    public pe.axiz.payments.coreintegration.domain.Movement reverse(String movementId, String key) {
      calls.add("reverse " + movementId + " " + key);
      return new pe.axiz.payments.coreintegration.domain.Movement(
          "MV-R", MovementStatus.REVERSED, movementId);
    }

    @Override
    public pe.axiz.payments.coreintegration.domain.Movement credit(
        MovementOrder order, String key) {
      calls.add("credit " + key);
      orders.add(order);
      return new pe.axiz.payments.coreintegration.domain.Movement(
          "MV-C", MovementStatus.POSTED, order.reference());
    }
  }

  final RecordingCore core = new RecordingCore();
  final EventMetrics metrics =
      new EventMetrics(new SimpleMeterRegistry(), MetricsSettings.defaults());
  final MovementsController movements =
      new MovementsController(
          new DebitAccountUseCase(
              core,
              new LockTemplate(
                  new InMemoryDistributedLock(Clock.systemUTC()), Duration.ofMillis(5)),
              metrics),
          new ReverseDebitUseCase(core),
          new CreditAccountUseCase(core, metrics));

  static MovementRequest movement(String holder) {
    return new MovementRequest()
        .accountId("1910012345678")
        .amount(new BigDecimal("150.75"))
        .currency("PEN")
        .reference("OP-1")
        .holderId(holder);
  }

  @Test
  void aDebitReachesTheCoreWithTheHolderAndTheCallersKey() {
    var response = movements.debitAccount("key-1", movement("C-1001"));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    Movement body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getMovementId()).isEqualTo("MV-D");
    assertThat(body.getStatus()).isEqualTo("POSTED");
    assertThat(body.getReference()).isEqualTo("OP-1");
    assertThat(core.calls).containsExactly("debit key-1");
    MovementOrder order = core.orders.get(0);
    assertThat(order.accountId()).isEqualTo("1910012345678");
    assertThat(order.amount().amount()).isEqualByComparingTo("150.75");
    assertThat(order.amount().currencyCode()).isEqualTo("PEN");
    assertThat(order.holderId()).isEqualTo("C-1001");
  }

  @Test
  void reversalsAndCreditsAnswer201() {
    var reversed = movements.reverseDebit("MV-D", "key-2");
    var credited = movements.creditAccount("key-3", movement(null));

    assertThat(reversed.getStatusCode().value()).isEqualTo(201);
    assertThat(reversed.getBody()).isNotNull();
    assertThat(reversed.getBody().getStatus()).isEqualTo("REVERSED");
    assertThat(credited.getStatusCode().value()).isEqualTo(201);
    assertThat(credited.getBody()).isNotNull();
    assertThat(credited.getBody().getMovementId()).isEqualTo("MV-C");
    assertThat(core.calls).containsExactly("reverse MV-D key-2", "credit key-3");
    assertThat(core.orders.get(0).holderId()).isNull();
  }

  @Test
  void aTransferIsSentWithTheCallersKey() {
    List<String> sent = new ArrayList<>();
    TransfersController transfers =
        new TransfersController(
            new SendTransferUseCase(
                (order, key) -> {
                  sent.add(order.creditorCci() + " " + order.amount().amount() + " " + key);
                  return new pe.axiz.payments.coreintegration.domain.Transfer(
                      "NET-1", TransferStatus.SETTLED, order.reference());
                },
                IdentifierRegistry.withDiscoveredTypes(),
                metrics));

    var response =
        transfers.sendTransfer(
            "key-4",
            new TransferRequest()
                .creditorCci("00219100123456789053")
                .amount(new BigDecimal("10.00"))
                .currency("PEN")
                .reference("E2E-1"));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    Transfer body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getTransferId()).isEqualTo("NET-1");
    assertThat(body.getStatus()).isEqualTo("SETTLED");
    assertThat(body.getReference()).isEqualTo("E2E-1");
    assertThat(sent).containsExactly("00219100123456789053 10.00 key-4");
  }

  @Test
  void inexactInputsNeverReachTheBankOrTheTransferNetwork() {
    TransfersController transfers =
        new TransfersController(
            new SendTransferUseCase(
                (order, key) -> {
                  throw new AssertionError("Inexact amount reached transfer network");
                },
                IdentifierRegistry.withDiscoveredTypes(),
                metrics));
    for (String amount : List.of("150.005", "150.755", "1e99999999")) {
      MovementRequest request = movement("C-1001").amount(new BigDecimal(amount));
      assertThatThrownBy(() -> movements.debitAccount("debit-key", request))
          .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
      assertThatThrownBy(() -> movements.creditAccount("credit-key", request))
          .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
      TransferRequest transfer =
          new TransferRequest()
              .creditorCci("00219100123456789053")
              .amount(new BigDecimal(amount))
              .currency("PEN")
              .reference("EXACT-1");
      assertThatThrownBy(() -> transfers.sendTransfer("transfer-key", transfer))
          .isInstanceOf(pe.axiz.core.errors.BusinessException.class);
    }
    assertThat(core.calls).isEmpty();
    assertThat(core.orders).isEmpty();
  }
}
