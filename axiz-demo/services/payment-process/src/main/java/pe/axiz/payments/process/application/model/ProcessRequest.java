package pe.axiz.payments.process.application.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Contrato de aplicación independiente de la serialización HTTP y de las anotaciones de validación.
 */
public record ProcessRequest(
    String paymentOrderId,
    String operationNumber,
    String debtorAccount,
    String creditorCci,
    BigDecimal amount,
    String currency,
    @Nullable String customerId) {}
