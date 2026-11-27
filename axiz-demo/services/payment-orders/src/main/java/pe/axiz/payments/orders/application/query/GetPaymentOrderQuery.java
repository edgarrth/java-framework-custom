package pe.axiz.payments.orders.application.query;

import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Una orden de pago.
 *
 * @param orderId id de la orden
 */
public record GetPaymentOrderQuery(String orderId) implements Query<PaymentOrder> {}
