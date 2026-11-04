package pe.axiz.payments.process.application.model;

import org.jspecify.annotations.Nullable;

/** Application contract independent of HTTP serialization and validation annotations. */
public record ProcessRequest(
    String paymentOrderId,
    String operationNumber,
    String debtorAccount,
    String creditorCci,
    java.math.BigDecimal amount,
    String currency,
    @Nullable String customerId) {}
