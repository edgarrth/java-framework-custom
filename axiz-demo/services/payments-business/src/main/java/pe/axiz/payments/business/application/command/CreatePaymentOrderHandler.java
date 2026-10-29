package pe.axiz.payments.business.application.command;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.core.money.Money;
import pe.axiz.core.time.CutoffTime;
import pe.axiz.framework.cqrs.CommandHandler;
import pe.axiz.payments.business.application.port.out.OperationNumbers;
import pe.axiz.payments.business.application.port.out.PaymentEvents;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.domain.PaymentErrors;
import pe.axiz.payments.business.domain.PaymentLimits;
import pe.axiz.payments.business.domain.PaymentOrder;

/**
 * Registers a payment order: validates the creditor CCI, assigns the processing date (business
 * calendar and cut-off time), reserves the amount in the customer's daily limit, rejects probable
 * duplicates, numbers the operation and publishes {@code payment-order.created} through the outbox,
 * all in one transaction.
 *
 * <p>Concurrency: the reservation is an atomic conditional update of the customer's row for that
 * day and currency, which stays locked until commit; the duplicate check runs after it, so two
 * concurrent orders of the same customer and day are serialized and can neither exceed the limit
 * together nor both pass as non-duplicates. The client's request key is unique per customer: a
 * retried request returns the order it already created (durable idempotency of the business
 * operation, independent of the HTTP idempotency store).
 */
public class CreatePaymentOrderHandler
    implements CommandHandler<CreatePaymentOrderCommand, PaymentOrder> {

  private final PaymentOrders orders;
  private final OperationNumbers numbers;
  private final PaymentEvents events;
  private final PaymentLimits limits;
  private final CutoffTime cutoff;
  private final IdentifierRegistry identifiers;
  private final IdGenerator ids;
  private final EventMetrics metrics;
  private final Clock clock;

  public CreatePaymentOrderHandler(
      PaymentOrders orders,
      OperationNumbers numbers,
      PaymentEvents events,
      PaymentLimits limits,
      CutoffTime cutoff,
      IdentifierRegistry identifiers,
      IdGenerator ids,
      EventMetrics metrics,
      Clock clock) {
    this.orders = orders;
    this.numbers = numbers;
    this.events = events;
    this.limits = limits;
    this.cutoff = cutoff;
    this.identifiers = identifiers;
    this.ids = ids;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public PaymentOrder handle(CreatePaymentOrderCommand c) {
    if (!identifiers.isValid("cci-strict", c.creditorCci())) {
      throw new BusinessException(PaymentErrors.INVALID_CREDITOR_ACCOUNT, "invalid cci", Map.of());
    }
    Money amount = Money.of(c.amount(), c.currency());
    String requestKey = c.requestKey();
    if (requestKey != null) {
      var existing = orders.findByRequestKey(c.customerId(), requestKey);
      if (existing.isPresent()) {
        return sameRequest(existing.get(), c, amount);
      }
    }
    LocalDate processingDate = cutoff.processingDate(clock.instant());
    Money limit = limits.limitFor(amount);
    if (amount.isGreaterThan(limit)
        || !orders.reserveDaily(c.customerId(), amount, processingDate, limit)) {
      throw limits.exceeded(
          limit, orders.reservedOn(c.customerId(), amount.currencyCode(), processingDate));
    }
    // after the reservation: the customer's day row is locked, concurrent orders wait here
    if (orders.exists(c.customerId(), c.creditorCci(), amount, processingDate)) {
      throw new BusinessException(PaymentErrors.POSSIBLE_DUPLICATE, "possible duplicate", Map.of());
    }
    PaymentOrder order =
        PaymentOrder.register(
            ids.newId(),
            numbers.next(),
            c.customerId(),
            c.debtorAccount(),
            c.creditorCci(),
            amount,
            c.description(),
            processingDate,
            clock.instant());
    orders.create(order, requestKey);
    events.created(order);
    metrics.record(
        MetricEvent.of("payment.order.created")
            .amount(amount)
            .outcome(Outcome.SUCCESS)
            .stage(cutoff.isBeforeCutoff(clock.instant()) ? "same-day" : "next-day"));
    return order;
  }

  /** A retried request returns its order; the same key for another order is an error. */
  private static PaymentOrder sameRequest(
      PaymentOrder existing, CreatePaymentOrderCommand c, Money amount) {
    boolean same =
        existing.amount().equals(amount)
            && existing.creditorCci().equals(c.creditorCci())
            && existing.debtorAccount().equals(c.debtorAccount());
    if (!same) {
      throw new BusinessException(PaymentErrors.REQUEST_KEY_REUSED, "request key reused", Map.of());
    }
    return existing;
  }
}
