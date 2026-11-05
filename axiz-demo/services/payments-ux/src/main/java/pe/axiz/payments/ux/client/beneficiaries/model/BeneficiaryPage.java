package pe.axiz.payments.ux.client.beneficiaries.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * BeneficiaryPage.
 *
 * @param items items
 * @param nextCursor nextCursor
 */
public record BeneficiaryPage(@Nullable List<Beneficiary> items, @Nullable String nextCursor) {
  public BeneficiaryPage {
    items = items == null ? null : List.copyOf(items);
  }
}
