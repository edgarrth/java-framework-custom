package pe.axiz.payments.business.application.query;

import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.framework.cqrs.Query;
import pe.axiz.payments.business.domain.PaymentOrder;

/**
 * Orders of a customer, newest first.
 *
 * @param customerId customer
 * @param page page to read
 */
public record ListPaymentOrdersQuery(String customerId, PageRequest page)
    implements Query<Page<PaymentOrder>> {}
