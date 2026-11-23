package pe.axiz.core.tokenization.vault;

/** El almacenamiento puede haber hecho commit; nunca se reinterpreta como conflicto condicional. */
public final class VaultUncertainException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public VaultUncertainException() {
    super("Vault write outcome is unknown");
  }
}
