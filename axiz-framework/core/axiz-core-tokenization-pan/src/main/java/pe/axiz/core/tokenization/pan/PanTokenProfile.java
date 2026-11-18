package pe.axiz.core.tokenization.pan;

import java.util.Map;
import pe.axiz.core.carddata.Pan;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenProfile;

/** PAN validation and canonical digits; no persistence or encryption algorithm. */
public final class PanTokenProfile implements TokenProfile {
  @Override
  public String id() {
    return "pan.luhn";
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public boolean cardData() {
    return true;
  }

  @Override
  public SensitiveValue canonicalize(SensitiveValue input) {
    return SensitiveValue.of(Pan.parse(input.reveal()).reveal());
  }

  @Override
  public Map<String, String> metadata(SensitiveValue canonical) {
    return Map.of("last4", Pan.parse(canonical.reveal()).last4());
  }
}
