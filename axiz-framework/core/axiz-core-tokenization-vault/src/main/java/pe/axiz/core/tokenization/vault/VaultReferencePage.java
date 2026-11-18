package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/** Bounded count of persisted references; even an orphan must block key retirement. */
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
