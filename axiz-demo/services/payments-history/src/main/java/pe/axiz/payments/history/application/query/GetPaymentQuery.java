package pe.axiz.payments.history.application.query;

import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.history.domain.PaymentHistory;

/**
 * Projected state of a payment (GET /v1/payments/{paymentId}).
 *
 * @param paymentId order id
 */
public record GetPaymentQuery(String paymentId) implements Query<PaymentHistory> {}
