package pe.axiz.payments.orders.application.query;

import pe.axiz.core.api.Page;
import pe.axiz.core.api.PageRequest;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.payments.orders.domain.PaymentOrder;

/**
 * Órdenes de un cliente, de la más reciente a la más antigua.
 *
 * @param customerId cliente
 * @param page página por leer
 */
public record ListPaymentOrdersQuery(String customerId, PageRequest page)
    implements Query<Page<PaymentOrder>> {}
