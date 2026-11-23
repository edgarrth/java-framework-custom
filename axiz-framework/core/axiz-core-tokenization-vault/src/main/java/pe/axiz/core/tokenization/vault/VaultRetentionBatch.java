package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Progreso acotado de la retención; las filas cambiadas requieren otro recorrido, nunca un borrado
 * forzado.
 */
public record VaultRetentionBatch(
    int inspected,
    int deleted,
    int absent,
    int changed,
    int retained,
    @Nullable String nextCursor) {
  public VaultRetentionBatch {
    if (inspected < 0
        || inspected > 100
        || deleted < 0
        || absent < 0
        || changed < 0
        || retained < 0
        || (long) deleted + absent + changed + retained != inspected
        || (nextCursor != null && (nextCursor.isEmpty() || nextCursor.length() > 1024)))
      throw new IllegalArgumentException("Invalid retention progress");
  }

  @Override
  public String toString() {
    return "VaultRetentionBatch[inspected="
        + inspected
        + ", deleted="
        + deleted
        + ", absent="
        + absent
        + ", changed="
        + changed
        + ", retained="
        + retained
        + "]";
  }
}
