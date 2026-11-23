package pe.axiz.core.fileformats;

import java.math.BigDecimal;

/**
 * Totales acumulados que se verifican contra el trailer de un archivo (cantidad de registros y suma
 * de montos).
 */
public final class ControlTotals {

  private long records;
  private BigDecimal sum = BigDecimal.ZERO;

  public void add(BigDecimal amount) {
    records++;
    sum = sum.add(amount);
  }

  public long records() {
    return records;
  }

  public BigDecimal sum() {
    return sum;
  }

  /** Falla cuando el trailer no coincide con lo leído. */
  public void verify(long expectedRecords, BigDecimal expectedSum, int trailerLine) {
    if (records != expectedRecords) {
      throw new FileFormatException(
          "Trailer record count " + expectedRecords + " differs from " + records, trailerLine);
    }
    if (sum.compareTo(expectedSum) != 0) {
      throw new FileFormatException(
          "Trailer amount " + expectedSum + " differs from " + sum, trailerLine);
    }
  }
}
