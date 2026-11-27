package pe.axiz.payments.history.application.command;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.OpenAccess;
import pe.axiz.payments.history.domain.PaymentStatus;

/**
 * payment-orders movió una orden a un estado nuevo.
 *
 * @param paymentId id de la orden
 * @param customerId cliente
 * @param status estado nuevo
 * @param reason motivo, cuando se indica
 * @param occurredAt momento del evento
 */
@OpenAccess(
    reason = "sent only by the payment-order.status-changed consumer, never exposed over HTTP")
public record RecordPaymentStatusChangedCommand(
    String paymentId,
    String customerId,
    PaymentStatus status,
    @Nullable String reason,
    Instant occurredAt)
    implements Command<Void> {}
