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
 * @param holderId customer who must own the account (debits)
 */
public record MovementRequest(
    String accountId,
    BigDecimal amount,
    String currency,
    String reference,
    @Nullable String holderId) {}
