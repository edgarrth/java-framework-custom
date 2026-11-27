package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Conteo acotado de referencias persistidas; incluso un huérfano debe bloquear el retiro de una
 * clave.
 */
public record VaultReferencePage(int references, @Nullable String nextCursor) {
  public VaultReferencePage {
    if (references < 0
        || references > 100
        || (nextCursor != null && (nextCursor.isEmpty() || nextCursor.length() > 1024)))
      throw new IllegalArgumentException("Invalid maintenance reference page");
  }

  @Override
  public String toString() {
    return "VaultReferencePage[count=" + references + "]";
  }
}
