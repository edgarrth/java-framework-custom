package pe.axiz.payments.orders.application.port.out;

import java.time.LocalDate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.domain.PaymentOrder;

/** Storage of payment orders. */
public interface PaymentOrders {

  /**
   * Stores changes of an existing order; never inserts a missing order. The use case loads and
   * saves within the same transaction. A missing persisted order is an infrastructure failure.
   */
  void save(PaymentOrder order);

  /**
   * Inserts a new order together with the client's request key, unique per customer, and writes it
   * immediately so a concurrent request with the same key fails here.
   *
   * @throws pe.axiz.core.errors.BusinessException {@code CONCURRENT_REQUEST} when another request
   *     with the same key was stored first
   */
  void create(PaymentOrder order, @Nullable String requestKey);

  /** The order a customer registered with a request key, if any. */
  Optional<PaymentOrder> findByRequestKey(String customerId, String requestKey);

  Optional<PaymentOrder> find(String id);

  Page<PaymentOrder> byCustomer(String customerId, PageRequest page);

  /**
   * Atomically adds {@code amount} to what the customer reserved for that processing date and
   * currency, only if the total stays within {@code limit}. The reservation row is locked until the
   * transaction ends, so concurrent orders of the same customer and day are serialized: two orders
   * can never both fit in the same remaining amount.
   *
   * @return false when the amount does not fit (nothing is reserved)
   */
  boolean reserveDaily(String customerId, Money amount, LocalDate processingDate, Money limit);

  /** Gives back the amount of an order that will not be paid (rejected). */
  void releaseDaily(String customerId, Money amount, LocalDate processingDate);

  /** Amount reserved by the customer for a processing date, in one currency. */
  Money reservedOn(String customerId, String currency, LocalDate processingDate);

  /** Whether the customer already ordered the same amount to the same creditor on that date. */
  boolean exists(String customerId, String creditorCci, Money amount, LocalDate processingDate);
}
