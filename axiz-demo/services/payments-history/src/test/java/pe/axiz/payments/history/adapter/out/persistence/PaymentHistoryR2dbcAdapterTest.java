package pe.axiz.payments.history.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Pageable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.money.Money;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The R2DBC adapter over an in-memory reactive repository: changes applied to the stored row (or a
 * new one), the creditor account kept as an encrypted column and pages with or without status.
 */
@ExtendWith(BlockHoundExtension.class)
class PaymentHistoryR2dbcAdapterTest {

  static final Duration T = Duration.ofSeconds(5);
  static final Instant AT = Instant.parse("2026-10-09T15:00:00Z");

  /** Rows by payment id; answers the derived queries the adapter uses. */
  static final class Rows {
    final Map<String, PaymentHistoryRow> rows = new LinkedHashMap<>();
    final List<String> calls = new ArrayList<>();

    PaymentHistoryRepository repository() {
      return (PaymentHistoryRepository)
          Proxy.newProxyInstance(
              PaymentHistoryRepository.class.getClassLoader(),
              new Class<?>[] {PaymentHistoryRepository.class},
              (proxy, method, args) -> {
                calls.add(method.getName());
                return switch (method.getName()) {
                  case "findByPaymentId" -> Mono.justOrEmpty(rows.get((String) args[0]));
                  case "save" -> {
                    PaymentHistoryRow row = (PaymentHistoryRow) args[0];
                    rows.put(row.toDomain().paymentId(), row);
                    yield Mono.just(row);
                  }
                  case "findByCustomerIdOrderByLastChangeAtDesc" ->
                      Flux.fromIterable(of((String) args[0], null, (Pageable) args[1]));
                  case "findByCustomerIdAndStatusOrderByLastChangeAtDesc" ->
                      Flux.fromIterable(of((String) args[0], (String) args[1], (Pageable) args[2]));
                  case "countByCustomerId" ->
                      Mono.just((long) of((String) args[0], null, Pageable.unpaged()).size());
                  case "countByCustomerIdAndStatus" ->
                      Mono.just(
                          (long) of((String) args[0], (String) args[1], Pageable.unpaged()).size());
                  case "toString" -> "Rows";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> throw new UnsupportedOperationException(method.getName());
                };
              });
    }

    List<PaymentHistoryRow> of(String customer, String status, Pageable page) {
      List<PaymentHistoryRow> mine =
          rows.values().stream()
              .filter(r -> r.toDomain().customerId().equals(customer))
              .filter(r -> status == null || r.toDomain().status().name().equals(status))
              .sorted(
                  Comparator.comparing((PaymentHistoryRow r) -> r.toDomain().lastChangeAt())
                      .reversed())
              .toList();
      return page.isUnpaged()
          ? mine
          : mine.stream().skip(page.getOffset()).limit(page.getPageSize()).toList();
    }
  }

  final Rows rows = new Rows();
  final PaymentHistoryR2dbcAdapter adapter = new PaymentHistoryR2dbcAdapter(rows.repository());

  static PaymentHistory.Registration registration() {
    return new PaymentHistory.Registration(
        "OP-1", "00219100123456789053", Money.of("150.75", "PEN"), LocalDate.of(2026, 10, 9));
  }

  @Test
  void aChangeCreatesTheRowAndLaterChangesUpdateIt() {
    PaymentHistory created =
        adapter
            .apply("P-1", h -> PaymentHistory.registered(h, "P-1", "C-1", registration(), AT))
            .block(T);
    assertThat(created).isNotNull();
    assertThat(created.status()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(created.creditorCci()).isEqualTo("00219100123456789053");

    PaymentHistory done =
        adapter
            .apply(
                "P-1",
                h ->
                    PaymentHistory.statusChanged(
                        h, "P-1", "C-1", PaymentStatus.COMPLETED, "settled", AT.plusSeconds(60)))
            .block(T);

    assertThat(done).isNotNull();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.operationNumber()).isEqualTo("OP-1");
    assertThat(rows.rows).hasSize(1);
    assertThat(adapter.find("P-1").block(T)).isEqualTo(done);
    assertThat(adapter.find("P-404").blockOptional(T)).isEmpty();
  }

  @Test
  void aStatusSeenBeforeTheRegistrationHasNoAccountYet() {
    PaymentHistory early =
        adapter
            .apply(
                "P-2",
                h ->
                    PaymentHistory.statusChanged(
                        h, "P-2", "C-1", PaymentStatus.PROCESSING, null, AT))
            .block(T);

    assertThat(early).isNotNull();
    assertThat(early.creditorCci()).isNull();
    assertThat(early.amount()).isNull();
  }

  @Test
  void pagesWithAndWithoutStatusCountTheirOwnTotal() {
    for (int i = 1; i <= 3; i++) {
      String id = "P-" + i;
      Instant at = AT.plusSeconds(i);
      adapter.apply(id, h -> PaymentHistory.registered(h, id, "C-1", registration(), at)).block(T);
    }
    adapter
        .apply(
            "P-2",
            h ->
                PaymentHistory.statusChanged(
                    h, "P-2", "C-1", PaymentStatus.REJECTED, "limit", AT.plusSeconds(30)))
        .block(T);

    Page<PaymentHistory> all = adapter.byCustomer("C-1", null, PageRequest.of(0, 2)).block(T);
    Page<PaymentHistory> rejected =
        adapter.byCustomer("C-1", PaymentStatus.REJECTED, PageRequest.of(0, 2)).block(T);

    assertThat(all).isNotNull();
    assertThat(all.content()).extracting(PaymentHistory::paymentId).containsExactly("P-2", "P-3");
    assertThat(all.metadata().totalElements()).isEqualTo(3);
    assertThat(rejected).isNotNull();
    assertThat(rejected.content()).extracting(PaymentHistory::paymentId).containsExactly("P-2");
    assertThat(rejected.metadata().totalElements()).isEqualTo(1);
    assertThat(rows.calls).contains("countByCustomerId", "countByCustomerIdAndStatus");
  }
}
