package pe.axiz.core.fileformats;

import java.math.BigDecimal;

/** Running totals checked against a file trailer (record count and amount sum). */
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

  /** Fails when the trailer does not match what was read. */
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
