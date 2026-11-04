package pe.axiz.payments.orders.application.command;

import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.payments.orders.application.port.out.PaymentEvents;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

/** Applies a status change and publishes {@code payment-order.status-changed}. */
public class ChangePaymentOrderStatusHandler
    implements CommandHandler<ChangePaymentOrderStatusCommand, PaymentOrder> {

  private final PaymentOrders orders;
  private final PaymentEvents events;
  private final EventMetrics metrics;

  public ChangePaymentOrderStatusHandler(
      PaymentOrders orders, PaymentEvents events, EventMetrics metrics) {
    this.orders = orders;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public PaymentOrder handle(ChangePaymentOrderStatusCommand c) {
    PaymentOrder order =
        orders
            .find(c.orderId())
            .orElseThrow(() -> new NotFoundException("payment-order", c.orderId()));
    if (order.status() == c.status()) {
      return order; // retried request: already applied
    }
    PaymentStatus previous = order.status();
    order.moveTo(c.status(), c.reason());
    orders.save(order);
    if (order.status() == PaymentStatus.REJECTED) {
      // the money will not move: give the amount back to the customer's daily limit
      orders.releaseDaily(order.customerId(), order.amount(), order.processingDate());
    }
    events.statusChanged(order, previous);
    if (order.status().isFinal()) {
      metrics.record(
          MetricEvent.of("payment.order.finished")
              .amount(order.amount())
              .outcome(
                  order.status() == PaymentStatus.COMPLETED ? Outcome.SUCCESS : Outcome.REJECTED));
    }
    return order;
  }
}
