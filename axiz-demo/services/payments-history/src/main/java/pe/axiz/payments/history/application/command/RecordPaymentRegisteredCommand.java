package pe.axiz.payments.history.application.command;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.OpenAccess;

/**
 * payment-orders registró una orden: se agrega al historial del cliente.
 *
 * @param paymentId id de la orden
 * @param operationNumber número de operación
 * @param customerId cliente
 * @param creditorCci cuenta de destino
 * @param amount monto
 * @param currency moneda
 * @param processingDate fecha de procesamiento
 * @param occurredAt momento del evento
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
