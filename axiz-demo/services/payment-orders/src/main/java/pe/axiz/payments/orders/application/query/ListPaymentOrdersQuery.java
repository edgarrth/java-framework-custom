package pe.axiz.payments.orders.application.query;

import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Orders of a customer, newest first.
 *
 * @param customerId customer
 * @param page page to read
 */
public record ListPaymentOrdersQuery(String customerId, PageRequest page)
    implements Query<Page<PaymentOrder>> {}
