package pe.axiz.payments.history.application.command;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import pe.axiz.framework.cqrs.Command;
import pe.axiz.framework.cqrs.OpenAccess;
import pe.axiz.payments.history.domain.PaymentStatus;

/**
 * payments-business moved an order to a new status.
 *
 * @param paymentId order id
 * @param customerId customer
 * @param status new status
 * @param reason reason, when given
 * @param occurredAt time of the event
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
