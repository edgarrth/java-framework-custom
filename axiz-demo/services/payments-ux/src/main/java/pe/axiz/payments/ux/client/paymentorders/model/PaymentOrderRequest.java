package pe.axiz.payments.ux.client.paymentorders.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * PaymentOrderRequest.
 *
 * @param customerId customerId
 * @param debtorAccount debtorAccount
 * @param creditorCci creditorCci
 * @param amount amount
 * @param currency currency
 * @param description description
 */
public record PaymentOrderRequest(
    String customerId,
    String debtorAccount,
    String creditorCci,
    BigDecimal amount,
    String currency,
    @Nullable String description) {}
