package pe.axiz.payments.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.storage.InMemoryObjectStorage;

class SettlementSummaryTaskletTest {

  @Test
  void writesTheSummaryNextToTheFile() throws Exception {
    InMemoryObjectStorage storage = new InMemoryObjectStorage();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SettlementSummaryTasklet tasklet =
        new SettlementSummaryTasklet(
            null, storage, new EventMetrics(registry, MetricsSettings.defaults()));

    tasklet.publish(
        "in/settlement/NET-SETTLE-20261009.txt",
        List.of(
            new SettlementSummaryTasklet.Total("PEN", "SETTLED", 2, new BigDecimal("170.75")),
            new SettlementSummaryTasklet.Total("PEN", "RETURNED", 1, new BigDecimal("20.00"))));

    try (var in =
        storage
            .get("out/settlement/NET-SETTLE-20261009.txt.summary.json")
            .orElseThrow()
            .content()) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(json).contains("\"status\":\"RETURNED\"").contains("170.75");
    }
    assertThat(registry.getMeters()).isNotEmpty();
  }
}
