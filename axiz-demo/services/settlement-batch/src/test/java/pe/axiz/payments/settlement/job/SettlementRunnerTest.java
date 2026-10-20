package pe.axiz.payments.settlement.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import pe.axiz.core.time.BusinessCalendar;

class SettlementRunnerTest {

  static final ZoneId LIMA = ZoneId.of("America/Lima");

  SettlementRunner runner(String explicitKey, Instant now) {
    return runner(null, null, explicitKey, now);
  }

  SettlementRunner runner(
      JobOperator jobs, SettlementSummaryTasklet summary, String explicitKey, Instant now) {
    BusinessCalendar calendar =
        BusinessCalendar.builder(LIMA).holiday(LocalDate.of(2026, 10, 8)).build();
    return new SettlementRunner(
        jobs,
        null,
        config -> Optional.empty(),
        new SettlementProperties(explicitKey, "in/settlement/", Duration.ofHours(1), true),
        calendar,
        summary,
        Clock.fixed(now, LIMA));
  }

  /** Summary step whose bucket either has the summary or lost it; records the rewrites. */
  static final class Summaries extends SettlementSummaryTasklet {
    final boolean present;
    final List<String> rewritten = new ArrayList<>();

    Summaries(boolean present) {
      super(null, null, null);
      this.present = present;
    }

    @Override
    public boolean exists(String fileKey) {
      return present;
    }

    @Override
    public void rewrite(String fileKey) {
      rewritten.add(fileKey);
    }
  }

  static JobOperator alreadyLoaded() {
    return (JobOperator)
        Proxy.newProxyInstance(
            JobOperator.class.getClassLoader(),
            new Class<?>[] {JobOperator.class},
            (p, m, a) -> {
              if (m.getName().equals("start")) {
                throw new JobInstanceAlreadyCompleteException("already complete");
              }
              throw new UnsupportedOperationException(m.getName());
            });
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

  @Test
  void aRerunOfALoadedFileWritesItsMissingSummaryAgain() throws Exception {
    Summaries lost = new Summaries(false);
    SettlementRunner r = runner(alreadyLoaded(), lost, null, Instant.parse("2026-10-09T11:00:00Z"));
    assertThat(r.launch("in/settlement/NET-SETTLE-20261007.txt")).isZero();
    assertThat(lost.rewritten).containsExactly("in/settlement/NET-SETTLE-20261007.txt");

    Summaries kept = new Summaries(true);
    assertThat(
            runner(alreadyLoaded(), kept, null, Instant.parse("2026-10-09T11:00:00Z"))
                .launch("in/settlement/NET-SETTLE-20261007.txt"))
        .isZero();
    assertThat(kept.rewritten).isEmpty();
  }
}
