package pe.axiz.core.tokenization.vault;

/**
 * Referencias corruptas o inconsistentes; no se exponen texto plano, claves ni errores del
 * proveedor.
 */
public final class VaultIntegrityException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public VaultIntegrityException() {
    super("Vault association integrity check failed");
  }
}
