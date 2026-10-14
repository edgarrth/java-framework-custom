package pe.axiz.payments.experience.client.paymentsbusiness.model;

import org.jspecify.annotations.Nullable;

/**
 * StatusChange.
 *
 * @param status status
 * @param reason reason
 */
public record StatusChange(PaymentStatus status, @Nullable String reason) {}
