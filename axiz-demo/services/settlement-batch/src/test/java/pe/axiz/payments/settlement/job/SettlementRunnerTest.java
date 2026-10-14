package pe.axiz.payments.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pe.axiz.core.time.BusinessCalendar;

class SettlementRunnerTest {

  static final ZoneId LIMA = ZoneId.of("America/Lima");

  SettlementRunner runner(String explicitKey, Instant now) {
    BusinessCalendar calendar =
        BusinessCalendar.builder(LIMA).holiday(LocalDate.of(2026, 10, 8)).build();
    return new SettlementRunner(
        null,
        null,
        config -> Optional.empty(),
        new SettlementProperties(explicitKey, "in/settlement/", Duration.ofHours(1)),
        calendar,
        Clock.fixed(now, LIMA));
  }

  @Test
  void loadsThePreviousBusinessDayByDefault() {
    // Friday 2026-10-09 06:00 Lima: previous business day is Wednesday 7 (Thursday 8 is a holiday)
    assertThat(runner(null, Instant.parse("2026-10-09T11:00:00Z")).inputKey())
        .isEqualTo("in/settlement/NET-SETTLE-20261007.txt");
  }

  @Test
  void anExplicitKeyReprocessesThatFile() {
    assertThat(runner("in/settlement/fix.txt", Instant.parse("2026-10-09T11:00:00Z")).inputKey())
        .isEqualTo("in/settlement/fix.txt");
  }

  @Test
  void whenAnotherReplicaHoldsTheLockNothingRuns() {
    SettlementRunner r = runner(null, Instant.parse("2026-10-09T11:00:00Z"));
    r.run(null);
    assertThat(r.getExitCode()).isZero();
  }
}
