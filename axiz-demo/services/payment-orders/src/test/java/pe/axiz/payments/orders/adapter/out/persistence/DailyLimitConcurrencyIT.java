package pe.axiz.payments.orders.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentErrors;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.spring.test.EphemeralSecrets;
import pe.axiz.spring.test.KafkaTestConfiguration;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.PostgresTestConfiguration;

/**
 * Regresión: con 10,000 ya usados bajo un límite de 30,000, dos órdenes concurrentes de 15,000 no
 * deben pasar ambas. Se ejecuta contra PostgreSQL en un contenedor (Docker).
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.messaging.kafka.authentication=configured",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@Import({
  PostgresTestConfiguration.class,
  KafkaTestConfiguration.class,
  LocalStackTestConfiguration.class
})
class DailyLimitConcurrencyIT {

  /**
   * Claves de cifrado de campos y de índice ciego: aleatorias en cada ejecución, nunca versionadas.
   */
  @DynamicPropertySource
  static void secrets(DynamicPropertyRegistry registry) {
    EphemeralSecrets.register(registry, "field-key-v1", "blind-index-key");
  }

  @Autowired PaymentOrders orders;
  @Autowired TransactionTemplate transactions;

  @Test
  void realPostgresConstraintFieldsDistinguishRequestKeysFromOtherDuplicates() {
    String customer = "C-" + UUID.randomUUID().toString().substring(0, 18);
    String request = UUID.randomUUID().toString();
    PaymentOrder first = newOrder(customer);
    transactions.executeWithoutResult(_ -> orders.create(first, request));
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(_ -> orders.create(newOrder(customer), request)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            failure ->
                assertThat(failure.definition()).isEqualTo(PaymentErrors.CONCURRENT_REQUEST));
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    _ ->
                        orders.create(
                            newOrder(customer, first.operationNumber()),
                            UUID.randomUUID().toString())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static PaymentOrder newOrder(String customer) {
    return newOrder(customer, "OP-" + UUID.randomUUID());
  }

  private static PaymentOrder newOrder(String customer, String operationNumber) {
    return PaymentOrder.register(
        UUID.randomUUID().toString(),
        operationNumber,
        customer,
        "1910012345678",
        "00219100123456789053",
        Money.of("10", "PEN"),
        "Constraint test",
        LocalDate.of(2026, 11, 5),
        Instant.parse("2026-11-05T00:00:00Z"));
  }

  @Test
  void concurrentReservationsNeverExceedTheLimit() throws Exception {
    String customer = "C-" + System.nanoTime() % 100_000_000;
    LocalDate day = LocalDate.of(2026, 10, 12);
    Money limit = Money.of("30000", "PEN");
    transactions.executeWithoutResult(
        _ ->
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
                    _ -> orders.reserveDaily(customer, Money.of("15000", "PEN"), day, limit));
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

    // una orden rechazada devuelve su monto
    transactions.executeWithoutResult(
        _ -> orders.releaseDaily(customer, Money.of("15000", "PEN"), day));
    assertThat(orders.reservedOn(customer, "PEN", day)).isEqualTo(Money.of("10000", "PEN"));
    transactions.executeWithoutResult(
        _ ->
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
          tx.setRollbackOnly(); // p. ej., falló luego la verificación de duplicados o el insert
        });
    assertThat(orders.reservedOn(customer, "PEN", day)).isEqualTo(Money.zero("PEN"));
  }
}
