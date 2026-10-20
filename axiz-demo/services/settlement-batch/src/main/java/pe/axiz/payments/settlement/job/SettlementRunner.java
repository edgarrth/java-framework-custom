package pe.axiz.payments.settlement.job;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import pe.axiz.core.time.BusinessCalendar;

/**
 * Entry point of the CronJob: works out which file to load and runs the job under a cluster lock
 * (ShedLock on Aurora), so a manual run never overlaps the scheduled one. The process exit code is
 * the job's: 0 when the file was loaded (or had already been), 1 when it failed. A file that was
 * already loaded is not loaded again; its summary is written again if it is missing.
 */
@Component
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementRunner implements ApplicationRunner, ExitCodeGenerator {

  private static final Logger log = LoggerFactory.getLogger(SettlementRunner.class);
  static final String LOCK = "settlement-job";

  private final JobOperator jobs;
  private final Job settlementJob;
  private final LockingTaskExecutor locks;
  private final SettlementProperties properties;
  private final BusinessCalendar calendar;
  private final SettlementSummaryTasklet summary;
  private final Clock clock;
  private final AtomicInteger exitCode = new AtomicInteger();

  public SettlementRunner(
      JobOperator jobs,
      Job settlementJob,
      LockProvider lockProvider,
      SettlementProperties properties,
      BusinessCalendar calendar,
      SettlementSummaryTasklet summary,
      Clock clock) {
    this.jobs = jobs;
    this.settlementJob = settlementJob;
    this.locks = new DefaultLockingTaskExecutor(lockProvider);
    this.properties = properties;
    this.calendar = calendar;
    this.summary = summary;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.runOnStartup()) {
      log.info("settlement.run-on-startup is false: no file is loaded at startup");
      return;
    }
    exitCode.set(load(inputKey()));
  }

  /**
   * Loads one file under the cluster lock.
   *
   * @param key object key of the file in the bucket
   * @return 0 when it was loaded, had already been loaded or another replica is loading it; 1 when
   *     it failed
   */
  public int load(String key) {
    LockingTaskExecutor.TaskResult<Integer> result;
    try {
      result =
          locks.executeWithLock(
              () -> launch(key),
              new LockConfiguration(
                  clock.instant(), LOCK, properties.maxDuration(), Duration.ZERO));
    } catch (Throwable e) {
      log.error("Settlement job could not run for {}", key, e);
      return 1;
    }
    if (!result.wasExecuted()) {
      log.warn("Settlement job is already running elsewhere; nothing to do");
      return 0;
    }
    Integer code = result.getResult();
    return code == null ? 1 : code;
  }

  int launch(String key) throws Exception {
    JobParameters params =
        new JobParametersBuilder()
            .addString(SettlementJobConfiguration.INPUT_KEY, key)
            .toJobParameters();
    try {
      JobExecution execution = jobs.start(settlementJob, params);
      log.info("Settlement job for {} ended {}", key, execution.getStatus());
      return execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
    } catch (JobInstanceAlreadyCompleteException e) {
      log.info("Settlement file {} was already loaded", key);
      if (!summary.exists(key)) {
        // e.g. the bucket was recreated: the rows are the source of truth, the summary follows them
        log.warn("Summary of {} is missing; writing it again from the loaded rows", key);
        summary.rewrite(key);
      }
      return 0;
    }
  }

  /** The configured key, or the file of the previous business day. */
  String inputKey() {
    if (properties.inputKey() != null && !properties.inputKey().isBlank()) {
      return properties.inputKey();
    }
    LocalDate today = LocalDate.now(clock.withZone(calendar.zone()));
    return SettlementFile.keyFor(properties.inputPrefix(), calendar.previousBusinessDay(today));
  }

  @Override
  public int getExitCode() {
    return exitCode.get();
  }
}
