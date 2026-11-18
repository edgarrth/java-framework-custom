package pe.axiz.core.carddata;

import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.sensitive.PanDetector;
import pe.axiz.core.sensitive.SelfMasking;

/** Non-reversible BIN/last-four representation, validated independently of a full PAN. */
public record TruncatedPan(String bin, String last4, int length) implements SelfMasking {
  public TruncatedPan {
    if (bin == null
        || last4 == null
        || !bin.matches("[0-9]{6}")
        || !last4.matches("[0-9]{4}")
        || length < PanDetector.MIN_DIGITS
        || length > PanDetector.MAX_DIGITS)
      throw new BusinessException(CardDataErrors.INVALID_PAN, "Invalid truncated card number");
  }

  public static TruncatedPan of(Pan pan) {
    return new TruncatedPan(pan.bin(), pan.last4(), pan.masked().length());
  }

  @Override
  public String masked() {
    return bin + "*".repeat(length - 10) + last4;
  }

  @Override
  public String toString() {
    return masked();
  }
}
