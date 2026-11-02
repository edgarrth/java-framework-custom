package pe.axiz.payments.history.application.query;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.history.domain.PaymentHistory;
import pe.axiz.payments.history.domain.PaymentStatus;

/**
 * Payments of a customer, latest change first (GET /v1/customers/{customerId}/payments).
 *
 * @param customerId customer
 * @param status only payments in this status, or all
 * @param page page to read
 */
public record ListCustomerPaymentsQuery(
    String customerId, @Nullable PaymentStatus status, PageRequest page)
    implements Query<Page<PaymentHistory>> {}
