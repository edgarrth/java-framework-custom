package pe.axiz.payments.coreintegration.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.payments.coreintegration.application.port.out.CoreAccounts;
import pe.axiz.payments.coreintegration.application.port.out.PaymentNetwork;
import pe.axiz.payments.coreintegration.domain.IntegrationErrors;
import pe.axiz.payments.coreintegration.domain.Movement;
import pe.axiz.payments.coreintegration.domain.MovementOrder;
import pe.axiz.payments.coreintegration.domain.MovementStatus;
import pe.axiz.payments.coreintegration.domain.Transfer;
import pe.axiz.payments.coreintegration.domain.TransferOrder;
import pe.axiz.payments.coreintegration.domain.TransferStatus;

class UseCasesTest {

  /** Core fake that records calls and detects overlapping debits on the same account. */
  static final class FakeCore implements CoreAccounts {
    final List<String> calls = new ArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    volatile int maxInFlight;

    @Override
    public Movement debit(MovementOrder order, String key) {
      int now = inFlight.incrementAndGet();
      maxInFlight = Math.max(maxInFlight, now);
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      inFlight.decrementAndGet();
      synchronized (calls) {
        calls.add("debit:" + key);
      }
      return new Movement("MV-" + key, MovementStatus.POSTED, order.reference());
    }

    @Override
    public Movement reverse(String movementId, String key) {
      calls.add("reverse:" + movementId);
      return new Movement("MV-R", MovementStatus.REVERSED, movementId);
    }

    @Override
    public Movement credit(MovementOrder order, String key) {
      calls.add("credit:" + key);
      return new Movement("MV-C", MovementStatus.POSTED, order.reference());
    }
  }

  final FakeCore core = new FakeCore();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final EventMetrics metrics = new EventMetrics(meters, MetricsSettings.defaults());
  final LockTemplate locks =
      new LockTemplate(new InMemoryDistributedLock(Clock.systemUTC()), Duration.ofMillis(5));

  static MovementOrder order(String account) {
    return new MovementOrder(account, Money.of("150.75", "PEN"), "OP-1");
  }

  @Test
  void debitsAreSerializedPerAccount() throws Exception {
    DebitAccountUseCase debit = new DebitAccountUseCase(core, locks, metrics);
    var pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    for (int i = 0; i < 4; i++) {
      String key = "k" + i;
      pool.submit(
          () -> {
            start.await();
            return debit.execute(new DebitAccountUseCase.Input(order("1910012345678"), key));
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    assertThat(core.calls).hasSize(4);
    assertThat(core.maxInFlight).isEqualTo(1);
  }

  @Test
  void creditAndReversalReachTheCoreWithTheCallersKey() {
    new CreditAccountUseCase(core, metrics)
        .execute(new CreditAccountUseCase.Input(order("1910012345678"), "key-c"));
    Movement reversed =
        new ReverseDebitUseCase(core).execute(new ReverseDebitUseCase.Input("MV-9", "key-r"));

    assertThat(core.calls).containsExactly("credit:key-c", "reverse:MV-9");
    assertThat(reversed.status()).isEqualTo(MovementStatus.REVERSED);
  }

  @Test
  void transfersToInvalidAccountsNeverReachTheNetwork() {
    List<String> sent = new ArrayList<>();
    PaymentNetwork network =
        (o, key) -> {
          sent.add(o.creditorCci());
          return new Transfer("NET-1", TransferStatus.SETTLED, o.reference());
        };
    SendTransferUseCase send =
        new SendTransferUseCase(network, IdentifierRegistry.withDiscoveredTypes(), metrics);

    assertThatThrownBy(
            () ->
                send.execute(
                    new SendTransferUseCase.Input(
                        new TransferOrder("00219100123456789099", Money.of("10", "PEN"), "OP-2"),
                        "k")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(IntegrationErrors.TRANSFER_REJECTED));
    assertThat(sent).isEmpty();

    Transfer ok =
        send.execute(
            new SendTransferUseCase.Input(
                new TransferOrder("00219100123456789053", Money.of("10", "PEN"), "OP-3"), "k"));
    assertThat(ok.status()).isEqualTo(TransferStatus.SETTLED);
    assertThat(sent).containsExactly("00219100123456789053");
  }

  @Test
  void ordersRejectNonPositiveAmounts() {
    assertThatThrownBy(() -> new MovementOrder("1910012345678", Money.of("0", "PEN"), "OP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(order("123").lockKey()).isEqualTo("account:123");
  }
}
