package pe.axiz.core.tokenization.vault;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Página de mantenimiento restringida. Su cursor opaco nunca debe aparecer en respuestas públicas
 * ni en logs.
 */
public record VaultScanPage(List<VaultRecord> records, @Nullable String nextCursor) {
  public VaultScanPage {
    if (records.size() > 100
        || (nextCursor != null && (nextCursor.isEmpty() || nextCursor.length() > 1024)))
      throw new IllegalArgumentException("Invalid vault maintenance page");
    records = List.copyOf(records);
  }

  @Override
  public String toString() {
    return "VaultScanPage[count=" + records.size() + ", more=" + (nextCursor != null) + "]";
  }
}
