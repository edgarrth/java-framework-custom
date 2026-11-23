package pe.axiz.structure.batch.spring;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricEvent;
import pe.axiz.core.metrics.Outcome;
import pe.axiz.structure.batch.JobOutcome;

/**
 * Métricas del job (evento {@code batch.run} con duración y registros), auditoría de cada ejecución
 * y una línea de log de resumen. El starter de batch lo adjunta a cada job.
 */
public class ObservedJobListener implements JobExecutionListener {

  private static final Logger LOG = LoggerFactory.getLogger("axiz.batch");

  private final @Nullable EventMetrics metrics;
  private final @Nullable AuditTrail audit;

  public ObservedJobListener(@Nullable EventMetrics metrics, @Nullable AuditTrail audit) {
    this.metrics = metrics;
    this.audit = audit;
  }

  @Override
  public void afterJob(JobExecution execution) {
    JobOutcome outcome = outcome(execution);
    LOG.info(
        "job={} status={} read={} written={} skipped={} durationMs={}",
        outcome.job(),
        outcome.status(),
        outcome.read(),
        outcome.written(),
        outcome.skipped(),
        outcome.duration().toMillis());
    if (metrics != null) {
      metrics.record(
          MetricEvent.of("batch.run")
              .category("operation")
              .stage(outcome.job())
              .outcome(outcome.successful() ? Outcome.SUCCESS : Outcome.ERROR)
              .value((double) outcome.read())
              .duration(outcome.duration()));
    }
    if (audit != null) {
      AuditTrail.Builder record =
          audit
              .record("batch.run", "job")
              .resourceId(outcome.job())
              .detail("read", outcome.read())
              .detail("written", outcome.written())
              .detail("skipped", outcome.skipped());
      if (outcome.successful()) {
        record.success();
      } else {
        record.failure(outcome.status().toLowerCase(Locale.ROOT));
      }
    }
  }

  static JobOutcome outcome(JobExecution execution) {
    long read = 0;
    long written = 0;
    long skipped = 0;
    for (StepExecution s : execution.getStepExecutions()) {
      read += s.getReadCount();
      written += s.getWriteCount();
      skipped += s.getSkipCount();
    }
    LocalDateTime start = execution.getStartTime();
    LocalDateTime end = execution.getEndTime();
    Duration duration = start != null && end != null ? Duration.between(start, end) : Duration.ZERO;
    return new JobOutcome(
        execution.getJobInstance().getJobName(),
        execution.getStatus().name(),
        read,
        written,
        skipped,
        duration);
  }
}
