package pe.axiz.payments.notifications.event;

import org.jspecify.annotations.Nullable;

/**
 * Payload de {@code pe.axiz.payments.payment-order.status-changed.v1} (payment-orders).
 *
 * @param orderId orden
 * @param customerId cliente
 * @param previous estado previo
 * @param status estado nuevo
 * @param reason motivo del cambio (vacío cuando no hay)
 */
public record PaymentOrderStatusChanged(
    String orderId, String customerId, String previous, String status, @Nullable String reason) {}
