package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/** Job progress; conflicts require another traversal and do not imply successful migration. */
public record VaultMaintenanceBatch(
    int inspected, int migrated, int changed, @Nullable String nextCursor) {
  public VaultMaintenanceBatch {
    if (inspected < 0
        || inspected > 100
        || migrated < 0
        || changed < 0
        || (long) migrated + changed > inspected
        || (nextCursor != null && (nextCursor.isEmpty() || nextCursor.length() > 1024)))
      throw new IllegalArgumentException("Invalid maintenance progress");
  }

  @Override
  public String toString() {
    return "VaultMaintenanceBatch[inspected="
        + inspected
        + ", migrated="
        + migrated
        + ", changed="
        + changed
        + "]";
  }
}
