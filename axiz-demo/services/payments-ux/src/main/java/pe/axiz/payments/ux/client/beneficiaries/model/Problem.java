package pe.axiz.payments.ux.client.beneficiaries.model;

import org.jspecify.annotations.Nullable;

/**
 * Problem.
 *
 * @param type type
 * @param title title
 * @param status status
 * @param detail detail
 * @param code code
 */
public record Problem(
    @Nullable String type,
    @Nullable String title,
    @Nullable Integer status,
    @Nullable String detail,
    @Nullable String code) {}
