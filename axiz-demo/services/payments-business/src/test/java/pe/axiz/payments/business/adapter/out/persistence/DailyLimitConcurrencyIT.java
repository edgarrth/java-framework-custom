package pe.axiz.payments.business.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.money.Money;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

/**
 * Report case (second review): with 10,000 already used under a 30,000 limit, concurrent orders of
 * 15,000 must not both pass. Runs against PostgreSQL in a container (Docker).
 */
@SpringBootTest
@Import({
  PostgresTestConfiguration.class,
  KafkaTestConfiguration.class,
  LocalStackTestConfiguration.class
})
class DailyLimitConcurrencyIT {

  /** Field encryption and blind index keys: random for every run, never committed. */
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
  }

  @Autowired PaymentOrders orders;
  @Autowired TransactionTemplate transactions;

  @Test
  void concurrentReservationsNeverExceedTheLimit() throws Exception {
    String customer = "C-" + System.nanoTime() % 100_000_000;
    LocalDate day = LocalDate.of(2026, 10, 12);
    Money limit = Money.of("30000", "PEN");
    transactions.executeWithoutResult(
        tx ->
            assertThat(orders.reserveDaily(customer, Money.of("10000", "PEN"), day, limit))
                .isTrue());

    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<Boolean>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                return transactions.execute(
                    tx -> orders.reserveDaily(customer, Money.of("15000", "PEN"), day, limit));
              }));
    }
    start.countDown();
    int accepted = 0;
    for (Future<Boolean> r : results) {
      accepted += Boolean.TRUE.equals(r.get(30, TimeUnit.SECONDS)) ? 1 : 0;
    }
    pool.shutdown();

    assertThat(accepted).isEqualTo(1);
    assertThat(orders.reservedOn(customer, "PEN", day)).isEqualTo(Money.of("25000", "PEN"));

    // a rejected order gives its amount back
    transactions.executeWithoutResult(
        tx -> orders.releaseDaily(customer, Money.of("15000", "PEN"), day));
    assertThat(orders.reservedOn(customer, "PEN", day)).isEqualTo(Money.of("10000", "PEN"));
    transactions.executeWithoutResult(
        tx ->
            assertThat(orders.reserveDaily(customer, Money.of("20000", "PEN"), day, limit))
                .isTrue());
  }

  @Test
  void aReservationThatRollsBackLeavesNoTrace() {
    String customer = "C-" + (System.nanoTime() % 100_000_000 + 1);
    LocalDate day = LocalDate.of(2026, 10, 13);
    Money limit = Money.of("30000", "PEN");
    transactions.executeWithoutResult(
        tx -> {
          orders.reserveDaily(customer, Money.of("20000", "PEN"), day, limit);
          tx.setRollbackOnly(); // e.g. the duplicate check or the insert failed afterwards
        });
    assertThat(orders.reservedOn(customer, "PEN", day)).isEqualTo(Money.zero("PEN"));
  }
}
