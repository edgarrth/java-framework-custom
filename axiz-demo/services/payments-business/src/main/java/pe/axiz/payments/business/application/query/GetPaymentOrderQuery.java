package pe.axiz.payments.business.application.query;

import pe.axiz.framework.cqrs.Query;
import pe.axiz.payments.business.domain.PaymentOrder;

/**
 * One payment order.
 *
 * @param orderId order id
 */
public record GetPaymentOrderQuery(String orderId) implements Query<PaymentOrder> {}
