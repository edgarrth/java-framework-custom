package pe.axiz.payments.process.adapter.out.bankingcore.model;

import org.jspecify.annotations.Nullable;

/**
 * Movement.
 *
 * @param movementId movementId
 * @param status status
 * @param reference reference
 */
public record Movement(
    @Nullable String movementId, @Nullable String status, @Nullable String reference) {}
