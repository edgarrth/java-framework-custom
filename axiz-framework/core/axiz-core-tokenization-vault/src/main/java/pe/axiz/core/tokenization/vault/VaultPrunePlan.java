package pe.axiz.core.tokenization.vault;

/** Proof that a complete traversal observed the current fingerprint on every remaining record. */
public final class VaultPrunePlan {
  private final FingerprintKeyState state;
  private final String obsoleteKey;

  VaultPrunePlan(FingerprintKeyState state, String obsoleteKey) {
    this.state = state;
    this.obsoleteKey = obsoleteKey;
  }

  public FingerprintKeyState state() {
    return state;
  }

  public String obsoleteKey() {
    return obsoleteKey;
  }
}
