package pe.axiz.payments.orders.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.domain.PaymentErrors;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/**
 * The JPA adapter over an in-memory Spring Data repository: what it stores (blind index instead of
 * the account for lookups, status and request key), how it maps rows back and how it tells a
 * concurrent request with the same key from any other integrity error.
 */
class PaymentOrderPersistenceAdapterTest {

  static final LocalDate DAY = LocalDate.of(2026, 10, 9);
  static final String CCI = "00219100123456789053";

  /** In-memory repository answering the methods the adapter calls; records the rest. */
  static final class Rows {
    final Map<UUID, PaymentOrderEntity> rows = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();
    RuntimeException nextSaveFailure;
    int reserveAnswer = 1;
    BigDecimal reserved = BigDecimal.ZERO;
    boolean exists;
    Pageable lastPageable;

    PaymentOrderJpaRepository repository() {
      return (PaymentOrderJpaRepository)
          Proxy.newProxyInstance(
              PaymentOrderJpaRepository.class.getClassLoader(),
              new Class<?>[] {PaymentOrderJpaRepository.class},
              (proxy, method, args) -> {
                calls.add(method.getName());
                return switch (method.getName()) {
                  case "saveAndFlush", "save" -> {
                    if (nextSaveFailure != null) {
                      RuntimeException failure = nextSaveFailure;
                      nextSaveFailure = null;
                      throw failure;
                    }
                    PaymentOrderEntity e = (PaymentOrderEntity) args[0];
                    rows.put(e.getId(), e);
                    yield e;
                  }
                  case "findById" -> Optional.ofNullable(rows.get((UUID) args[0]));
                  case "findByCustomerIdAndRequestKey" ->
                      rows.values().stream()
                          .filter(
                              e ->
                                  e.getCustomerId().equals(args[0])
                                      && args[1].equals(requestKey(e)))
                          .findFirst();
                  case "findByCustomerId" -> {
                    lastPageable = (Pageable) args[1];
                    List<PaymentOrderEntity> mine =
                        rows.values().stream()
                            .filter(e -> e.getCustomerId().equals(args[0]))
                            .toList();
                    yield new PageImpl<>(mine, lastPageable, mine.size() + 10L);
                  }
                  case "reserve" -> reserveAnswer;
                  case "release" -> 1;
                  case "reserved" -> reserved;
                  case "existsByCustomerIdAndCreditorCciIndexAndAmountAndProcessingDateAndStatusNot" -> {
                    calls.add("exists:" + args[1] + ":" + args[4]);
                    yield exists;
                  }
                  case "toString" -> "Rows";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              });
    }

    static Object requestKey(PaymentOrderEntity e) {
      try {
        Field f = PaymentOrderEntity.class.getDeclaredField("requestKey");
        f.setAccessible(true);
        return Objects.requireNonNullElse(f.get(e), "");
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  final Rows rows = new Rows();
  final BlindIndex blindIndex = new BlindIndex("k".repeat(32).getBytes(StandardCharsets.UTF_8), 16);
  final PaymentOrderPersistenceAdapter adapter =
      new PaymentOrderPersistenceAdapter(rows.repository(), blindIndex);

  static PaymentOrder order(UUID id, String customer) {
    return PaymentOrder.register(
        id.toString(),
        "OP20261009-00000001",
        customer,
        "1910012345678",
        CCI,
        Money.of("150.75", "PEN"),
        "Rent",
        DAY,
        Instant.parse("2026-10-09T15:00:00Z"));
  }

  static DataIntegrityViolationException violation(String constraint) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new PSQLException(
            new ServerErrorMessage("C23505\u0000Mclave duplicada\u0000n" + constraint + "\u0000")));
  }

  @Test
  void createsRowsThatMapBackToTheSameOrder() {
    UUID id = UUID.randomUUID();
    adapter.create(order(id, "C-1"), "req-1");

    PaymentOrderEntity row = rows.rows.get(id);
    assertThat(row.getStatus()).isEqualTo("RECEIVED");
    assertThat(row.getCreditorCci()).isEqualTo(CCI);
    assertThat(rows.calls).contains("saveAndFlush");

    PaymentOrder back = adapter.find(id.toString()).orElseThrow();
    assertThat(back.id()).isEqualTo(id.toString());
    assertThat(back.customerId()).isEqualTo("C-1");
    assertThat(back.debtorAccount()).isEqualTo("1910012345678");
    assertThat(back.amount()).isEqualTo(Money.of("150.75", "PEN"));
    assertThat(back.description()).isEqualTo("Rent");
    assertThat(back.processingDate()).isEqualTo(DAY);
    assertThat(back.status()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(back.createdAt()).isEqualTo(Instant.EPOCH);
    assertThat(adapter.findByRequestKey("C-1", "req-1")).isPresent();
    assertThat(adapter.findByRequestKey("C-1", "req-2")).isEmpty();
    assertThat(adapter.find("not-a-uuid")).isEmpty();
    assertThat(adapter.find(UUID.randomUUID().toString())).isEmpty();
  }

  @Test
  void savingUpdatesOnlyAnExistingRowAndNeverInserts() {
    UUID id = UUID.randomUUID();
    PaymentOrder order = order(id, "C-1");
    adapter.create(order, null);

    order.moveTo(PaymentStatus.REJECTED, "insufficient funds");
    adapter.save(order);
    assertThat(rows.rows.get(id).getStatus()).isEqualTo("REJECTED");
    assertThat(rows.rows.get(id).getStatusReason()).isEqualTo("insufficient funds");

    UUID other = UUID.randomUUID();
    int savedBefore = java.util.Collections.frequency(rows.calls, "save");
    assertThatThrownBy(() -> adapter.save(order(other, "C-2")))
        .isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
    assertThat(rows.rows).doesNotContainKey(other);
    assertThat(java.util.Collections.frequency(rows.calls, "save")).isEqualTo(savedBefore);
  }

  @Test
  void aRequestKeyStoredFirstByAnotherRequestIsAConcurrentRequest() {
    rows.nextSaveFailure = violation(PaymentOrderPersistenceAdapter.REQUEST_KEY_CONSTRAINT);
    assertThatThrownBy(() -> adapter.create(order(UUID.randomUUID(), "C-1"), "req-1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(PaymentErrors.CONCURRENT_REQUEST));

    DataIntegrityViolationException other = violation("payment_order_pkey");
    rows.nextSaveFailure = other;
    assertThatThrownBy(() -> adapter.create(order(UUID.randomUUID(), "C-1"), "req-2"))
        .isSameAs(other);

    DataIntegrityViolationException withoutKey =
        violation(PaymentOrderPersistenceAdapter.REQUEST_KEY_CONSTRAINT);
    rows.nextSaveFailure = withoutKey;
    assertThatThrownBy(() -> adapter.create(order(UUID.randomUUID(), "C-1"), null))
        .isSameAs(withoutKey);
  }

  @Test
  void messageTextAndOtherSqlStatesDoNotImpersonateTheRequestKeyConstraint() {
    for (SQLException cause :
        List.of(
            new SQLException(
                "duplicate " + PaymentOrderPersistenceAdapter.REQUEST_KEY_CONSTRAINT, "23505"),
            new PSQLException(
                new ServerErrorMessage(
                    "C23503\u0000Mreferencia inválida\u0000n"
                        + PaymentOrderPersistenceAdapter.REQUEST_KEY_CONSTRAINT
                        + "\u0000")),
            new PSQLException(
                new ServerErrorMessage("C23505\u0000Mclave duplicada sin nombre\u0000")))) {
      var failure = new DataIntegrityViolationException("insert", cause);
      rows.nextSaveFailure = failure;
      assertThatThrownBy(() -> adapter.create(order(UUID.randomUUID(), "C-1"), "req"))
          .isSameAs(failure);
    }
  }

  @Test
  void structuredConstraintCanBeNestedInTheSqlExceptionChain() {
    var batch = new SQLException("batch failed");
    batch.setNextException(
        (SQLException) violation(PaymentOrderPersistenceAdapter.REQUEST_KEY_CONSTRAINT).getCause());
    rows.nextSaveFailure = new DataIntegrityViolationException("insert", batch);
    assertThatThrownBy(() -> adapter.create(order(UUID.randomUUID(), "C-1"), "req"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(PaymentErrors.CONCURRENT_REQUEST));
  }

  @Test
  void pagesAreNewestFirstWithTheStoreTotal() {
    // the store reports 10 more rows than this page holds
    adapter.create(order(UUID.randomUUID(), "C-1"), null);
    adapter.create(order(UUID.randomUUID(), "C-2"), null);

    Page<PaymentOrder> page = adapter.byCustomer("C-1", PageRequest.of(0, 1));

    assertThat(page.content()).extracting(PaymentOrder::customerId).containsExactly("C-1");
    assertThat(page.metadata().totalElements()).isEqualTo(11);
    assertThat(rows.lastPageable.getPageSize()).isEqualTo(1);
    assertThat(rows.lastPageable.getSort().getOrderFor("createdAt").isDescending()).isTrue();
  }

  @Test
  void dailyLimitsAndDuplicatesGoToTheStore() {
    Money amount = Money.of("150.75", "PEN");
    Money limit = Money.of("1000", "PEN");

    assertThat(adapter.reserveDaily("C-1", amount, DAY, limit)).isTrue();
    rows.reserveAnswer = 0;
    assertThat(adapter.reserveDaily("C-1", amount, DAY, limit)).isFalse();
    adapter.releaseDaily("C-1", amount, DAY);
    rows.reserved = new BigDecimal("150.75");
    assertThat(adapter.reservedOn("C-1", "PEN", DAY)).isEqualTo(amount);

    rows.exists = true;
    assertThat(adapter.exists("C-1", CCI, amount, DAY)).isTrue();
    String index = blindIndex.index(CCI, PaymentOrderPersistenceAdapter.CCI_CONTEXT);
    assertThat(rows.calls).contains("release", "exists:" + index + ":REJECTED");
    assertThat(index).doesNotContain(CCI);
  }
}
