package pe.axiz.payments.history.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.payments.history.application.port.out.PaymentHistories;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;
import reactor.core.publisher.Mono;

/** Test double of the R2DBC adapter. */
public final class InMemoryPaymentHistories implements PaymentHistories {

  public final Map<String, PaymentHistory> entries = new ConcurrentHashMap<>();

  @Override
  public Mono<PaymentHistory> apply(String paymentId, Change change) {
    return Mono.fromSupplier(() -> entries.compute(paymentId, (_, v) -> change.apply(v)));
  }

  @Override
  public Mono<PaymentHistory> find(String paymentId) {
    return Mono.justOrEmpty(entries.get(paymentId));
  }

  @Override
  public Mono<Page<PaymentHistory>> byCustomer(
      String customerId, @Nullable PaymentStatus status, PageRequest page) {
    return Mono.fromSupplier(
        () -> {
          List<PaymentHistory> all =
              entries.values().stream()
                  .filter(h -> h.customerId().equals(customerId))
                  .filter(h -> status == null || h.status() == status)
                  .sorted(Comparator.comparing(PaymentHistory::lastChangeAt).reversed())
                  .toList();
          List<PaymentHistory> content =
              all.stream().skip(page.offset()).limit(page.size()).toList();
          return Page.of(content, page, all.size());
        });
  }
}
