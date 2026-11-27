package pe.axiz.structure.batch.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.id.IdGenerator;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricNames;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.structure.batch.JobOutcome;

/** Lo que deja un job terminado en el log, las métricas y el rastro de auditoría. */
class ObservedJobListenerTest {

  private static JobExecution execution(BatchStatus status, boolean timed) {
    JobExecution job = new JobExecution(7L, new JobInstance(3L, "settlement"), new JobParameters());
    job.setStatus(status);
    if (timed) {
      job.setStartTime(LocalDateTime.of(2026, 10, 23, 2, 0, 0));
      job.setEndTime(LocalDateTime.of(2026, 10, 23, 2, 0, 42));
    }
    StepExecution load = new StepExecution(1L, "load", job);
    load.setReadCount(100);
    load.setWriteCount(98);
    load.setReadSkipCount(2);
    StepExecution post = new StepExecution(2L, "post", job);
    post.setReadCount(98);
    post.setWriteCount(98);
    job.addStepExecutions(List.of(load, post));
    return job;
  }

  @Test
  void theOutcomeAddsUpEveryStep() {
    JobOutcome outcome = ObservedJobListener.outcome(execution(BatchStatus.COMPLETED, true));
    assertEquals("settlement", outcome.job());
    assertEquals(198, outcome.read());
    assertEquals(196, outcome.written());
    assertEquals(2, outcome.skipped());
    assertEquals(Duration.ofSeconds(42), outcome.duration());
    assertTrue(outcome.successful());

    assertEquals(
        Duration.ZERO,
        ObservedJobListener.outcome(execution(BatchStatus.STARTED, false)).duration(),
        "a job without both times has no duration");
  }

  @Test
  void aRunIsMeasuredAndAuditedAsSuccessOrFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    EventMetrics metrics = new EventMetrics(registry, MetricsSettings.defaults());
    List<AuditEvent> audited = new CopyOnWriteArrayList<>();
    AuditTrail audit =
        new AuditTrail(
            "batch-service",
            audited::add,
            ObjectMasker.defaults(),
            IdGenerator.uuidV7(),
            Clock.fixed(Instant.parse("2026-10-23T02:00:42Z"), ZoneOffset.UTC));
    ObservedJobListener listener = new ObservedJobListener(metrics, audit);

    listener.afterJob(execution(BatchStatus.COMPLETED, true));
    listener.afterJob(execution(BatchStatus.FAILED, true));

    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tag("event", "batch.run")
            .tag("outcome", "success")
            .counter()
            .count());
    assertEquals(
        1.0,
        registry
            .get(MetricNames.COUNT)
            .tag("event", "batch.run")
            .tag("outcome", "error")
            .counter()
            .count());
    assertEquals(2, audited.size());
    assertEquals(AuditEvent.Outcome.SUCCESS, audited.get(0).outcome());
    assertEquals("settlement", audited.get(0).resourceId());
    assertEquals(198L, ((Number) audited.get(0).details().get("read")).longValue());
    assertEquals(AuditEvent.Outcome.FAILURE, audited.get(1).outcome());
    assertEquals("failed", audited.get(1).errorCode());
  }

  @Test
  void withoutMetricsOrAuditOnlyTheLogLineIsWritten() {
    new ObservedJobListener(null, null).afterJob(execution(BatchStatus.COMPLETED, false));
  }

  /** Job que muestra qué listeners recibió. */
  static final class RecordingJob extends SimpleJob {
    final List<JobExecutionListener> listeners = new CopyOnWriteArrayList<>();

    RecordingJob() {
      super("settlement");
      setJobRepository(new ResourcelessJobRepository());
    }

    @Override
    public void registerJobExecutionListener(JobExecutionListener listener) {
      listeners.add(listener);
      super.registerJobExecutionListener(listener);
    }
  }

  @Test
  void everyJobOfTheContextGetsTheListener() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizBatchAutoConfiguration.class))
        .withBean(RecordingJob.class, RecordingJob::new)
        .withBean("notAJob", String.class, () -> "plain bean")
        .run(
            ctx -> {
              ObservedJobListener listener = ctx.getBean(ObservedJobListener.class);
              assertThat(ctx.getBean(RecordingJob.class).listeners).containsExactly(listener);
              assertEquals("plain bean", ctx.getBean("notAJob"));
            });

    ObservedJobListenerPostProcessor withoutListener =
        new ObservedJobListenerPostProcessor(
            new StaticListableBeanFactory().getBeanProvider(ObservedJobListener.class));
    RecordingJob job = new RecordingJob();
    assertSame(job, withoutListener.postProcessAfterInitialization(job, "job"));
    assertTrue(job.listeners.isEmpty());
  }

  @Test
  void batchServicesRunWithoutWebServerAndOnVirtualThreads() {
    MockEnvironment env = new MockEnvironment();
    AxizBatchEnvironmentPostProcessor epp = new AxizBatchEnvironmentPostProcessor();
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals("none", env.getProperty("spring.main.web-application-type"));
    assertEquals("true", env.getProperty("spring.threads.virtual.enabled"));
    assertEquals("embedded", env.getProperty("spring.batch.jdbc.initialize-schema"));
    assertEquals(Integer.MAX_VALUE, epp.getOrder());
  }
}
