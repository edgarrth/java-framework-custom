package pe.axiz.core.tokenization.vault;

/** Storage may have committed; never reinterpret this as a conditional conflict. */
public final class VaultUncertainException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public VaultUncertainException() {
    super("Vault write outcome is unknown");
  }
}
