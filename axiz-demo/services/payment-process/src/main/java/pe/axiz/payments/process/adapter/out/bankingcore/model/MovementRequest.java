package pe.axiz.payments.process.adapter.out.bankingcore.model;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * MovementRequest.
 *
 * @param accountId accountId
 * @param amount amount
 * @param currency currency
 * @param reference reference
 * @param holderId cliente que debe ser titular de la cuenta (débitos)
 */
public record MovementRequest(
    String accountId,
    BigDecimal amount,
    String currency,
    String reference,
    @Nullable String holderId) {}
