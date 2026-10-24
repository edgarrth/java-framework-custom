package pe.axiz.core.identifiers.banking;

import java.util.Optional;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;

/**
 * Resolves the card scheme of a PAN (or of its leading digits) from an authoritative source: the
 * IIN/BIN table the organization receives from the schemes or its processor, versioned and
 * replaceable without deploying code. Use it for routing, authorization, acceptance, pricing and
 * fraud decisions; {@link CardBrand#guess} is only a display hint.
 */
public interface CardSchemeResolver {

  /**
   * The scheme of the card.
   *
   * @param pan card number (or at least its first 6 to 8 digits); only digits
   * @return the scheme, empty when the table has no range for it (treat as not accepted)
   */
  Optional<CardBrand> resolve(String pan);

  /** Where the ranges come from (table name, version and date), for audit and logs. */
  String source();
}
