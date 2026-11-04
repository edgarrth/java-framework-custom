package pe.axiz.payments.orders.application.query;

import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * One payment order.
 *
 * @param orderId order id
 */
public record GetPaymentOrderQuery(String orderId) implements Query<PaymentOrder> {}
