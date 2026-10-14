package pe.axiz.payments.experience.client.beneficiariescrud.model;

import org.jspecify.annotations.Nullable;

/**
 * Beneficiary.
 *
 * @param id id
 * @param customerId customerId
 * @param alias alias
 * @param holderName holderName
 * @param cci cci
 * @param currency currency
 */
public record Beneficiary(
    @Nullable String id,
    @Nullable String customerId,
    @Nullable String alias,
    @Nullable String holderName,
    @Nullable String cci,
    @Nullable String currency) {}
