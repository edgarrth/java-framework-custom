package pe.axiz.structure.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.fileformats.FileFormatException;
import pe.axiz.core.fileformats.FixedField;
import pe.axiz.core.fileformats.FixedWidthLayout;

class RecordReaderTest {

  record Movement(String account, BigDecimal amount) {}

  @Test
  void csvWithHeaderAndLineNumbers() throws Exception {
    String data = "account,amount\nA1,10.50\n\nA2,x\n";
    try (CsvRecordReader<Movement> r =
        new CsvRecordReader<>(
            new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8,
            ',',
            true,
            row -> new Movement(row.get(0), new BigDecimal(row.get(1))))) {
      assertEquals(new Movement("A1", new BigDecimal("10.50")), r.read());
      FileFormatException e = assertThrows(FileFormatException.class, r::read);
      assertEquals(4, e.line());
    }
  }

  @Test
  void fixedWidthSkipsHeaderAndTrailer() throws Exception {
    FixedWidthLayout layout =
        new FixedWidthLayout(
            "mov",
            List.of(FixedField.text("account", 1, 4), FixedField.decimal("amount", 5, 6, 2)));
    String data = "H20260101\nA001001050\nA002000200\nT000002\n";
    try (FixedWidthRecordReader<Movement> r =
        new FixedWidthRecordReader<>(
            new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8,
            layout,
            m -> new Movement((String) m.get("account"), (BigDecimal) m.get("amount")),
            line -> line.startsWith("H") || line.startsWith("T"))) {
      assertEquals(new Movement("A001", new BigDecimal("10.50")), r.read());
      assertEquals(new Movement("A002", new BigDecimal("2.00")), r.read());
      assertNull(r.read());
      assertEquals("T000002", r.trailer());
    }
  }
}
