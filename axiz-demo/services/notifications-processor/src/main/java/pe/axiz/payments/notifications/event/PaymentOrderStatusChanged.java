package pe.axiz.payments.notifications.event;

import org.jspecify.annotations.Nullable;

/**
 * Payload of {@code pe.axiz.payments.payment-order.status-changed.v1} (payment-orders).
 *
 * @param orderId order
 * @param customerId customer
 * @param previous previous status
 * @param status new status
 * @param reason reason of the change (empty when none)
 */
public record PaymentOrderStatusChanged(
    String orderId, String customerId, String previous, String status, @Nullable String reason) {}
