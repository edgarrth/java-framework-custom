package pe.axiz.payments.settlement.job;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.fileformats.FileFormatException;
import pe.axiz.core.fileformats.FixedField;
import pe.axiz.core.fileformats.FixedWidthLayout;
import pe.axiz.structure.batch.FixedWidthRecordReader;

/**
 * Formato del archivo diario de liquidación de la red de pagos (ancho fijo, ASCII seguro en
 * ISO-8859-1):
 *
 * <pre>
 * H yyyyMMdd sender(10)                                  cabecera
 * D e2e(20) transfer(12) cci(20) amount(15,2) ccy(3) S|R yyyyMMddHHmmss   detalle (86 caracteres)
 * T count(9) total(17,2)                                 trailer (totales de control)
 * </pre>
 */
public final class SettlementFile {

  static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** Formato del registro de detalle. */
  public static final FixedWidthLayout DETAIL =
      new FixedWidthLayout(
          "settlement-detail",
          List.of(
              FixedField.text("type", 1, 1),
              FixedField.text("endToEndId", 2, 20),
              FixedField.text("transferId", 22, 12),
              FixedField.text("cci", 34, 20),
              FixedField.decimal("amount", 54, 15, 2),
              FixedField.text("currency", 69, 3),
              FixedField.text("status", 72, 1),
              FixedField.text("settledAt", 73, 14)));

  /** Formato del registro trailer. */
  public static final FixedWidthLayout TRAILER =
      new FixedWidthLayout(
          "settlement-trailer",
          List.of(
              FixedField.text("type", 1, 1),
              FixedField.integer("count", 2, 9),
              FixedField.decimal("total", 11, 17, 2)));

  private SettlementFile() {}

  /**
   * Lector de los registros de detalle; se omiten las líneas de cabecera y trailer (el trailer se
   * conserva).
   */
  public static FixedWidthRecordReader<SettlementLine> reader(InputStream in) {
    return new FixedWidthRecordReader<>(
        in,
        StandardCharsets.ISO_8859_1,
        DETAIL,
        SettlementFile::line,
        text -> text.startsWith("H") || text.startsWith("T"));
  }

  static SettlementLine line(Map<String, @Nullable Object> f) {
    if (!"D".equals(f.get("type"))) {
      throw new FileFormatException("unexpected record type " + f.get("type"), 0);
    }
    String status =
        switch (String.valueOf(f.get("status"))) {
          case "S" -> "SETTLED";
          case "R" -> "RETURNED";
          default -> throw new FileFormatException("unknown status " + f.get("status"), 0);
        };
    return new SettlementLine(
        text(f, "endToEndId"),
        text(f, "transferId"),
        text(f, "cci"),
        (BigDecimal) Objects.requireNonNull(f.get("amount"), "amount"),
        text(f, "currency"),
        status,
        LocalDateTime.parse(text(f, "settledAt"), STAMP));
  }

  /** Cantidad y total declarados por la línea trailer. */
  public record Trailer(long count, BigDecimal total) {}

  public static Trailer trailer(@Nullable String line, int lineNumber) {
    if (line == null || !line.startsWith("T")) {
      throw new FileFormatException("settlement file without trailer", lineNumber);
    }
    Map<String, @Nullable Object> t = TRAILER.parse(line, lineNumber);
    return new Trailer(
        ((Number) Objects.requireNonNull(t.get("count"))).longValue(),
        (BigDecimal) Objects.requireNonNull(t.get("total")));
  }

  /** Clave de objeto por defecto del archivo de un día. */
  public static String keyFor(String prefix, LocalDate day) {
    return prefix + "NET-SETTLE-" + day.format(DateTimeFormatter.BASIC_ISO_DATE) + ".txt";
  }

  private static String text(Map<String, @Nullable Object> f, String name) {
    Object v = f.get(name);
    if (v == null || v.toString().isBlank()) {
      throw new FileFormatException("missing " + name, 0);
    }
    return v.toString().strip();
  }
}
