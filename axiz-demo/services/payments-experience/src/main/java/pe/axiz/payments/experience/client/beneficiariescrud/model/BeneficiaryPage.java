package pe.axiz.payments.experience.client.beneficiariescrud.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * BeneficiaryPage.
 *
 * @param items items
 * @param nextCursor nextCursor
 */
public record BeneficiaryPage(@Nullable List<Beneficiary> items, @Nullable String nextCursor) {}
