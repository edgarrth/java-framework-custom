package pe.axiz.core.tokenization.vault;

/** Corrupted or inconsistent references; no plaintext, keys or provider error are exposed. */
public final class VaultIntegrityException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public VaultIntegrityException() {
    super("Vault association integrity check failed");
  }
}
