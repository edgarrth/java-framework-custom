package pe.axiz.payments.history.application.query;

import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.history.domain.PaymentHistory;

/**
 * Estado proyectado de un pago (GET /v1/payments/{paymentId}).
 *
 * @param paymentId id de la orden
 */
public record GetPaymentQuery(String paymentId) implements Query<PaymentHistory> {}
