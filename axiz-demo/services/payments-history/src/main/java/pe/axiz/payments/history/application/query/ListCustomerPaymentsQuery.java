package pe.axiz.payments.history.application.query;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;

/**
 * Pagos de un cliente, con el cambio más reciente primero (GET
 * /v1/customers/{customerId}/payments).
 *
 * @param customerId cliente
 * @param status solo los pagos en este estado, o todos
 * @param page página por leer
 */
public record ListCustomerPaymentsQuery(
    String customerId, @Nullable PaymentStatus status, PageRequest page)
    implements Query<Page<PaymentHistory>> {}
