package pe.axiz.payments.bankingcore.application.usecase;

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
import pe.axiz.core.lock.InMemoryDistributedLock;
import pe.axiz.core.lock.LockObserver;
import pe.axiz.core.lock.LockTemplate;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.money.Money;
import pe.axiz.payments.bankingcore.application.port.out.CoreAccounts;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.payments.bankingcore.domain.MovementStatus;

class UseCasesTest {

  /**
   * Fake del core que registra las llamadas y detecta débitos superpuestos sobre una misma cuenta.
   */
  static final class FakeCore implements CoreAccounts {
    final List<String> calls = new ArrayList<>();
    final AtomicInteger inFlight = new AtomicInteger();
    volatile int maxInFlight;
    final CountDownLatch attempts = new CountDownLatch(4);

    @Override
    public Movement debit(MovementOrder order, String key) {
      int now = inFlight.incrementAndGet();
      maxInFlight = Math.max(maxInFlight, now);
      try {
        assertThat(attempts.await(5, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException _) {
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
  final InMemoryDistributedLock lockStore = new InMemoryDistributedLock(Clock.systemUTC());
  final LockTemplate locks =
      new LockTemplate(
          (resource, lease) -> {
            var acquired = lockStore.tryAcquire(resource, lease);
            core.attempts.countDown();
            return acquired;
          },
          Duration.ofMillis(5),
          LockObserver.NONE);

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
  void ordersRejectNonPositiveAmounts() {
    assertThatThrownBy(() -> new MovementOrder("1910012345678", Money.of("0", "PEN"), "OP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(order("123").lockKey()).isEqualTo("account:123");
  }
}
