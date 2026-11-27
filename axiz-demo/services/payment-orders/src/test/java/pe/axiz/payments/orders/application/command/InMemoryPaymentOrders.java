package pe.axiz.payments.orders.application.command;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentOrder;

/** {@link PaymentOrders} en memoria: órdenes, claves de solicitud y reservas diarias. */
public final class InMemoryPaymentOrders implements PaymentOrders {
  public final Map<String, PaymentOrder> rows = new HashMap<>();
  public final Map<String, String> requestKeys = new HashMap<>();
  public final Map<String, Money> reserved = new HashMap<>();

  @Override
  public PaymentOrder create(PaymentOrder order, @Nullable String requestKey) {
    rows.put(order.id(), order);
    if (requestKey != null) {
      requestKeys.put(order.customerId() + "|" + requestKey, order.id());
    }
    return order;
  }

  @Override
  public Optional<PaymentOrder> findByRequestKey(String customerId, String requestKey) {
    return Optional.ofNullable(requestKeys.get(customerId + "|" + requestKey)).map(rows::get);
  }

  @Override
  public boolean reserveDaily(String customerId, Money amount, LocalDate date, Money limit) {
    String key = customerId + "|" + amount.currencyCode() + "|" + date;
    Money total = reserved.getOrDefault(key, Money.zero(amount.currencyCode())).plus(amount);
    if (total.isGreaterThan(limit)) {
      return false;
    }
    reserved.put(key, total);
    return true;
  }

  @Override
  public void releaseDaily(String customerId, Money amount, LocalDate date) {
    reserved.computeIfPresent(
        customerId + "|" + amount.currencyCode() + "|" + date, (_, v) -> v.minus(amount));
  }

  @Override
  public Money reservedOn(String customerId, String currency, LocalDate date) {
    return reserved.getOrDefault(customerId + "|" + currency + "|" + date, Money.zero(currency));
  }

  @Override
  public void save(PaymentOrder order) {
    rows.put(order.id(), order);
  }

  @Override
  public Optional<PaymentOrder> find(String id) {
    return Optional.ofNullable(rows.get(id));
  }

  @Override
  public Page<PaymentOrder> byCustomer(String customerId, PageRequest page) {
    List<PaymentOrder> all =
        rows.values().stream().filter(o -> o.customerId().equals(customerId)).toList();
    return Page.of(all, page, all.size());
  }

  @Override
  public boolean exists(String customerId, String cci, Money amount, LocalDate date) {
    return rows.values().stream()
        .anyMatch(
            o ->
                o.customerId().equals(customerId)
                    && o.creditorCci().equals(cci)
                    && o.amount().equals(amount)
                    && o.processingDate().equals(date));
  }
}
