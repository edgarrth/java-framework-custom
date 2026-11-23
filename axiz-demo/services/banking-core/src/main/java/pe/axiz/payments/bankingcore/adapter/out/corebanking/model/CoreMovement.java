package pe.axiz.payments.bankingcore.adapter.out.corebanking.model;

import org.jspecify.annotations.Nullable;

/**
 * CoreMovement.
 *
 * @param mvId mvId
 * @param state P contabilizado, R revertido
 */
public record CoreMovement(@Nullable String mvId, @Nullable String state) {}
