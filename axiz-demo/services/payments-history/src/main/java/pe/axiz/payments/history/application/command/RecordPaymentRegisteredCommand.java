package pe.axiz.payments.history.application.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.framework.cqrs.Command;
import pe.axiz.framework.cqrs.OpenAccess;

/**
 * payments-business registered an order: add it to the customer's history.
 *
 * @param paymentId order id
 * @param operationNumber operation number
 * @param customerId customer
 * @param creditorCci destination account
 * @param amount amount
 * @param currency currency
 * @param processingDate processing date
 * @param occurredAt time of the event
 */
@OpenAccess(reason = "sent only by the payment-order.created consumer, never exposed over HTTP")
public record RecordPaymentRegisteredCommand(
    String paymentId,
    String operationNumber,
    String customerId,
    @Sensitive(level = SensitivityLevel.MEDIUM, mask = MaskType.DOCUMENT) String creditorCci,
    BigDecimal amount,
    String currency,
    LocalDate processingDate,
    Instant occurredAt)
    implements Command<Void> {}
