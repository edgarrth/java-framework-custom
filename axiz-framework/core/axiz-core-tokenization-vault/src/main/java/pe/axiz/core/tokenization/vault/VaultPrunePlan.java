package pe.axiz.core.tokenization.vault;

/** Prueba de que un recorrido completo observó la huella actual en cada registro restante. */
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
