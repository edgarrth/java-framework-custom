package pe.axiz.payments.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import pe.axiz.core.identifiers.IdentifierRegistry;

class SettlementLineProcessorTest {

  final SettlementLineProcessor processor =
      new SettlementLineProcessor(IdentifierRegistry.withDiscoveredTypes());

  static SettlementLine line(String cci) {
    return new SettlementLine(
        "OP-1",
        "NET-1",
        cci,
        BigDecimal.TEN,
        "pen",
        "SETTLED",
        LocalDateTime.of(2026, 10, 12, 0, 0));
  }

  @Test
  void normalizesValidLinesAndFiltersInvalidAccounts() {
    assertThat(processor.process(line(SettlementFiles.VALID_CCI)).currency()).isEqualTo("PEN");
    assertThat(processor.process(line("00219100123456789099"))).isNull();
  }
}
