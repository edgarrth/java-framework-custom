/**
 * Object-level authorization of the commands and queries (who may act on which customer and order).
 * Policies run in the CQRS pipeline before idempotency, transactions and handlers.
 */
@NullMarked
package pe.axiz.payments.orders.application.access;

import org.jspecify.annotations.NullMarked;
