package pe.axiz.framework.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import pe.axiz.core.fileformats.FixedField;
import pe.axiz.core.fileformats.FixedWidthLayout;

/** Edge cases of the record readers and the job outcome. */
class RecordReaderEdgeCasesTest {

  @Test
  void outcomeMapsToTheExitCodeOfTheCronJob() {
    JobOutcome ok = new JobOutcome("settlement", "COMPLETED", 3, 3, 0, Duration.ofSeconds(2));
    JobOutcome failed = new JobOutcome("settlement", "FAILED", 3, 1, 0, Duration.ofSeconds(2));
    assertTrue(ok.successful());
    assertEquals(0, ok.exitCode());
    assertFalse(failed.successful());
    assertEquals(1, failed.exitCode());
  }

  @Test
  void csvWithoutHeaderKeepsSingleColumnValuesAndSkipsBlankLines() throws Exception {
    String data = "A1\n\n   \nA2\n";
    try (CsvRecordReader<String> r = csv(data, false, row -> String.join("|", row))) {
      assertEquals("A1", r.read());
      assertEquals("A2", r.read());
      assertNull(r.read());
      assertNull(r.read(), "the end of the file stays the end");
      assertTrue(r.line() >= 4);
    }
  }

  @Test
  void csvRowsWithSeveralColumnsAreNeverTakenForBlankLines() throws Exception {
    try (CsvRecordReader<List<String>> r = csv(" ,\nx,y\n", false, row -> row)) {
      assertEquals(List.of(" ", ""), r.read());
      assertEquals(List.of("x", "y"), r.read());
      assertNull(r.read());
    }
  }

  @Test
  void anEmptyCsvWithAHeaderHasNoRecords() throws Exception {
    try (CsvRecordReader<List<String>> r = csv("", true, row -> row)) {
      assertNull(r.read());
    }
  }

  @Test
  void fixedWidthSkipsBlankLinesAndCountsThem() throws Exception {
    FixedWidthLayout layout = new FixedWidthLayout("acc", List.of(FixedField.text("account", 1, 4)));
    try (FixedWidthRecordReader<Object> r =
        new FixedWidthRecordReader<>(
            new ByteArrayInputStream("\nA001\n  \nA002\n".getBytes(StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8,
            layout,
            m -> m.get("account"),
            line -> false)) {
      assertEquals("A001", r.read());
      assertEquals(2, r.line());
      assertEquals("A002", r.read());
      assertEquals(4, r.line());
      assertNull(r.read());
      assertNull(r.trailer(), "no header or trailer in this file");
    }
  }

  @Test
  void aReadFailureOfTheFileIsUnchecked() throws Exception {
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("connection reset");
          }
        };
    FixedWidthLayout layout = new FixedWidthLayout("acc", List.of(FixedField.text("account", 1, 4)));
    try (FixedWidthRecordReader<Object> r =
        new FixedWidthRecordReader<>(
            broken, StandardCharsets.UTF_8, layout, m -> m, line -> false)) {
      UncheckedIOException e = assertThrows(UncheckedIOException.class, r::read);
      assertInstanceOf(IOException.class, e.getCause());
    }
  }

  private static <T> CsvRecordReader<T> csv(
      String data, boolean header, Function<List<String>, T> mapper) {
    return new CsvRecordReader<>(
        new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)),
        StandardCharsets.UTF_8,
        ',',
        header,
        mapper);
  }
}
