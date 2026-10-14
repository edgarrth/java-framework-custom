package pe.axiz.core.fileformats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FileFormatsTest {

  @Test
  void csvReadsQuotedFieldsAndLineBreaks() throws Exception {
    String csv =
        "id,name,note\r\n1,\"Pérez, Ana\",\"dijo \"\"hola\"\"\"\n2,Bob,\"line1\nline2\"\n3,,\n";
    List<List<String>> rows = new ArrayList<>();
    try (CsvReader r = new CsvReader(new StringReader(csv), ',')) {
      r.forEach(rows::add);
    }
    assertEquals(List.of("id", "name", "note"), rows.get(0));
    assertEquals(List.of("1", "Pérez, Ana", "dijo \"hola\""), rows.get(1));
    assertEquals(List.of("2", "Bob", "line1\nline2"), rows.get(2));
    assertEquals(List.of("3", "", ""), rows.get(3));
    assertEquals(4, rows.size());
    CsvReader bad = new CsvReader(new StringReader("a,\"unterminated"), ',');
    assertThrows(FileFormatException.class, bad::next);
    assertNull(new CsvReader(new StringReader(""), ';').next());
  }

  @Test
  void csvWriterRoundTripsAndPreventsInjection() throws Exception {
    StringWriter out = new StringWriter();
    try (CsvWriter w = new CsvWriter(out, ',', true)) {
      w.write(Arrays.asList("a,b", "x\"y", "=SUM(A1)", null, "-5"));
    }
    assertEquals("\"a,b\",\"x\"\"y\",'=SUM(A1),,'-5\r\n", out.toString());
    List<String> back = new CsvReader(new StringReader(out.toString()), ',').next();
    assertEquals(List.of("a,b", "x\"y", "'=SUM(A1)", "", "'-5"), back);
  }

  @Test
  void fixedWidthParseAndFormat() {
    FixedWidthLayout detail =
        new FixedWidthLayout(
            "detail",
            List.of(
                FixedField.text("type", 1, 1),
                FixedField.text("account", 2, 10),
                FixedField.decimal("amount", 12, 12, 2),
                FixedField.date("date", 24, 8, "yyyyMMdd"),
                FixedField.integer("seq", 32, 5)));
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("type", "D");
    values.put("account", "ACC123");
    values.put("amount", new BigDecimal("1500.25"));
    values.put("date", LocalDate.of(2026, 1, 31));
    values.put("seq", 7);
    String line = detail.format(values);
    assertEquals("DACC123    0000001500252026013100007", line);
    Map<String, Object> parsed = detail.parse(line, 1);
    assertEquals("ACC123", parsed.get("account"));
    assertEquals(new BigDecimal("1500.25"), parsed.get("amount"));
    assertEquals(LocalDate.of(2026, 1, 31), parsed.get("date"));
    assertEquals(BigInteger.valueOf(7), parsed.get("seq"));
    assertEquals(36, detail.recordLength());
    assertThrows(FileFormatException.class, () -> detail.parse("short", 2));
    assertThrows(
        FileFormatException.class, () -> detail.parse("DACC123    00000ABC00252026013100007", 3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FixedWidthLayout(
                "x", List.of(FixedField.text("a", 1, 5), FixedField.text("b", 5, 2))));
    values.put("account", "TOO-LONG-ACCOUNT");
    assertThrows(IllegalArgumentException.class, () -> detail.format(values));
    assertThrows(IllegalArgumentException.class, () -> FixedField.date("d", 1, 8, null));
  }

  @Test
  void controlTotals() {
    ControlTotals t = new ControlTotals();
    t.add(new BigDecimal("10.50"));
    t.add(new BigDecimal("4.50"));
    t.verify(2, new BigDecimal("15.00"), 9);
    assertThrows(FileFormatException.class, () -> t.verify(3, new BigDecimal("15"), 9));
    assertEquals(
        9,
        assertThrows(FileFormatException.class, () -> t.verify(2, new BigDecimal("16"), 9)).line());
  }

  @Test
  void ebcdicAndPackedDecimal() {
    byte[] e = Ebcdic.encode("HELLO 123", Ebcdic.IBM037);
    assertEquals((byte) 0xC8, e[0]);
    assertEquals("HELLO 123", Ebcdic.decode(e, Ebcdic.IBM037));
    byte[] packed = Ebcdic.pack(new BigDecimal("-1234.56"), 2, 4);
    assertArrayEquals(new byte[] {0x01, 0x23, 0x45, 0x6D}, packed);
    assertEquals(new BigDecimal("-1234.56"), Ebcdic.unpack(packed, 2));
    assertEquals(new BigDecimal("12.3"), Ebcdic.unpack(new byte[] {0x12, 0x3F}, 1));
    assertThrows(
        IllegalArgumentException.class, () -> Ebcdic.pack(new BigDecimal("123456789"), 0, 2));
    assertThrows(IllegalArgumentException.class, () -> Ebcdic.unpack(new byte[] {0x12, 0x31}, 0));
    assertThrows(
        IllegalArgumentException.class, () -> Ebcdic.unpack(new byte[] {(byte) 0xA2, 0x3C}, 0));
  }

  @Test
  void checksums() {
    assertEquals(
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        Checksums.sha256(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));
    assertEquals(907060870L, Checksums.crc32("hello".getBytes(StandardCharsets.UTF_8)));
  }
}
