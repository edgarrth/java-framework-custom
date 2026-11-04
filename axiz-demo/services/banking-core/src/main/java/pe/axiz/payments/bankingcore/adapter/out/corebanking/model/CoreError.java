package pe.axiz.payments.bankingcore.adapter.out.corebanking.model;

import org.jspecify.annotations.Nullable;

/**
 * CoreError.
 *
 * @param code code
 * @param message message
 */
public record CoreError(@Nullable String code, @Nullable String message) {}
