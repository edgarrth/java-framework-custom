package pe.axiz.payments.settlement.job;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pe.axiz.core.storage.InMemoryObjectStorage;
import pe.axiz.core.storage.ObjectMetadata;

/** Construye archivos de liquidación para pruebas con los formatos de producción. */
final class SettlementFiles {

  static final String VALID_CCI = "00219100123456789053";

  private SettlementFiles() {}

  static String detail(String e2e, String cci, String amount, String status) {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("type", "D");
    v.put("endToEndId", e2e);
    v.put("transferId", "NET-" + e2e.substring(e2e.length() - 3));
    v.put("cci", cci);
    v.put("amount", new BigDecimal(amount));
    v.put("currency", "PEN");
    v.put("status", status);
    v.put("settledAt", "20261012103000");
    return SettlementFile.DETAIL.format(v);
  }

  static String trailer(long count, String total) {
    return SettlementFile.TRAILER.format(
        Map.of("type", "T", "count", count, "total", new BigDecimal(total)));
  }

  static String file(List<String> lines) {
    return "H20261012NETWORK   \n" + String.join("\n", lines) + "\n";
  }

  static void put(InMemoryObjectStorage storage, String key, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.ISO_8859_1);
    storage.put(
        key, new ByteArrayInputStream(bytes), ObjectMetadata.of("text/plain", bytes.length));
  }
}
