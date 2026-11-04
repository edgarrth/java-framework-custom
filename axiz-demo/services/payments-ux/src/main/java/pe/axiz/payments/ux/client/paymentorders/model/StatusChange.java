package pe.axiz.payments.ux.client.paymentorders.model;

import org.jspecify.annotations.Nullable;

/**
 * StatusChange.
 *
 * @param status status
 * @param reason reason
 */
public record StatusChange(PaymentStatus status, @Nullable String reason) {}
