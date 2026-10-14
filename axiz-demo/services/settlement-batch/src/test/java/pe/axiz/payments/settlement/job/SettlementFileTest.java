package pe.axiz.payments.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.fileformats.FileFormatException;
import pe.axiz.core.storage.InMemoryObjectStorage;

class SettlementFileTest {

  @Test
  void parsesDetailsAndKeepsTheTrailer() throws Exception {
    String content =
        SettlementFiles.file(
            List.of(
                SettlementFiles.detail(
                    "OP20261011-00000001", SettlementFiles.VALID_CCI, "150.75", "S"),
                SettlementFiles.detail(
                    "OP20261011-00000002", SettlementFiles.VALID_CCI, "20.00", "R"),
                SettlementFiles.trailer(2, "170.75")));
    try (var reader =
        SettlementFile.reader(
            new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)))) {
      SettlementLine first = reader.read();
      SettlementLine second = reader.read();
      assertThat(reader.read()).isNull();

      assertThat(first.endToEndId()).isEqualTo("OP20261011-00000001");
      assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("150.75"));
      assertThat(first.status()).isEqualTo("SETTLED");
      assertThat(first.settledAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 30));
      assertThat(second.status()).isEqualTo("RETURNED");
      assertThat(SettlementFile.trailer(reader.trailer(), reader.line()))
          .isEqualTo(new SettlementFile.Trailer(2, new BigDecimal("170.75")));
    }
  }

  @Test
  void rejectsUnknownStatusesAndMissingTrailers() {
    String bad =
        SettlementFiles.detail("OP20261011-00000003", SettlementFiles.VALID_CCI, "1.00", "X");
    assertThatThrownBy(
            () ->
                SettlementFile.reader(
                        new ByteArrayInputStream(bad.getBytes(StandardCharsets.ISO_8859_1)))
                    .read())
        .isInstanceOf(FileFormatException.class);
    assertThatThrownBy(() -> SettlementFile.trailer(null, 3))
        .isInstanceOf(FileFormatException.class);
  }

  @Test
  void controlTotalsGuardTheLoad() throws Exception {
    InMemoryObjectStorage storage = new InMemoryObjectStorage();
    String d =
        SettlementFiles.detail("OP20261011-00000001", SettlementFiles.VALID_CCI, "150.75", "S");
    SettlementFiles.put(
        storage, "ok.txt", SettlementFiles.file(List.of(d, SettlementFiles.trailer(1, "150.75"))));
    SettlementFiles.put(
        storage,
        "short.txt",
        SettlementFiles.file(List.of(d, SettlementFiles.trailer(2, "300.00"))));

    assertThat(ControlTotalsTasklet.verify(storage, "ok.txt")).isEqualTo(1);
    assertThatThrownBy(() -> ControlTotalsTasklet.verify(storage, "short.txt"))
        .isInstanceOf(FileFormatException.class)
        .hasMessageContaining("count");
    assertThatThrownBy(() -> ControlTotalsTasklet.verify(storage, "missing.txt"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void dailyKeys() {
    assertThat(SettlementFile.keyFor("in/settlement/", LocalDate.of(2026, 10, 9)))
        .isEqualTo("in/settlement/NET-SETTLE-20261009.txt");
    assertThat(SettlementSummaryTasklet.summaryKey("in/settlement/NET-SETTLE-20261009.txt"))
        .isEqualTo("out/settlement/NET-SETTLE-20261009.txt.summary.json");
  }

  @Test
  void theSampleFileOfTheLocalDemoIsValid() throws Exception {
    InMemoryObjectStorage storage = new InMemoryObjectStorage();
    try (var in = getClass().getResourceAsStream("/settlement/NET-SETTLE-SAMPLE.txt")) {
      SettlementFiles.put(
          storage, "sample.txt", new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
    }
    assertThat(ControlTotalsTasklet.verify(storage, "sample.txt")).isEqualTo(3);
  }
}
