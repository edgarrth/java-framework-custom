package pe.axiz.core.carddata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;
import pe.axiz.core.sensitive.PanDetector;
import pe.axiz.core.sensitive.SelfMasking;

/** Validated PAN; logs and default serialization use masked(), never reveal(). */
public final class Pan implements SelfMasking {
  private final String digits;

  private Pan(String digits) {
    this.digits = digits;
  }

  public static Pan parse(String value) {
    if (value == null || value.length() > 64) throw invalid();
    String digits = value.replaceAll("[\\s-]", "");
    if (!PanDetector.isPan(digits)) throw invalid();
    return new Pan(digits);
  }

  public static boolean looksLikePan(String value) {
    return value != null
        && value.length() <= 64
        && PanDetector.isPan(value.replaceAll("[\\s-]", ""));
  }

  private static BusinessException invalid() {
    return new BusinessException(CardDataErrors.INVALID_PAN, "Invalid card number");
  }

  public String reveal() {
    return digits;
  }

  public String bin() {
    return digits.substring(0, 6);
  }

  public String last4() {
    return digits.substring(digits.length() - 4);
  }

  /** Display hint only. Routing requires a governed CardSchemeResolver. */
  public CardBrand brand() {
    return CardBrand.guess(digits);
  }

  @Override
  public String masked() {
    return bin() + "*".repeat(digits.length() - 10) + last4();
  }

  @Override
  public String toString() {
    return masked();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof Pan pan
        && MessageDigest.isEqual(
            digits.getBytes(StandardCharsets.US_ASCII),
            pan.digits.getBytes(StandardCharsets.US_ASCII));
  }

  @Override
  public int hashCode() {
    return last4().hashCode();
  }
}
