package pe.axiz.payments.coreintegration.adapter.out.paymentnetwork.model;

import org.jspecify.annotations.Nullable;

/**
 * NetworkError.
 *
 * @param code code
 * @param message message
 */
public record NetworkError(@Nullable String code, @Nullable String message) {}
