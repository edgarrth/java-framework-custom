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
import pe.axiz.core.commons.ReviewedState;
import pe.axiz.core.time.BusinessCalendar;

/**
 * Punto de entrada del CronJob: determina qué archivo cargar y ejecuta el job bajo un lock del
 * clúster (ShedLock en Aurora), de modo que una ejecución manual nunca se superpone con la
 * programada. El código de salida del proceso es el del job: 0 cuando el archivo se cargó (o ya
 * estaba cargado), 1 cuando falló. Un archivo ya cargado no se vuelve a cargar; su resumen se
 * escribe de nuevo si falta.
 */
@Component
@EnableConfigurationProperties(SettlementProperties.class)
public class SettlementRunner implements ApplicationRunner, ExitCodeGenerator {

  private static final Logger LOG = LoggerFactory.getLogger(SettlementRunner.class);
  static final String LOCK = "settlement-job";

  private final JobOperator jobs;
  private final Job settlementJob;
  private final LockingTaskExecutor locks;
  private final SettlementProperties properties;
  private final BusinessCalendar calendar;
  private final SettlementSummaryTasklet summary;
  private final Clock clock;

  @ReviewedState(
      reason =
          "One startup run publishes its process exit status; AtomicInteger makes the "
              + "value visible to the shutdown thread. No business data is retained.")
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
      LOG.info("settlement.run-on-startup is false: no file is loaded at startup");
      return;
    }
    exitCode.set(load(inputKey()));
  }

  /**
   * Carga un archivo bajo el lock del clúster.
   *
   * @param key clave de objeto del archivo en el bucket
   * @return 0 cuando se cargó, ya estaba cargado u otra réplica lo está cargando; 1 cuando falló
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
      LOG.error(
          "Settlement job could not run for {}; failureType={}", key, e.getClass().getSimpleName());
      return 1;
    }
    if (!result.wasExecuted()) {
      LOG.warn("Settlement job is already running elsewhere; nothing to do");
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
      LOG.info("Settlement job for {} ended {}", key, execution.getStatus());
      return execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1;
    } catch (JobInstanceAlreadyCompleteException _) {
      LOG.info("Settlement file {} was already loaded", key);
      if (!summary.exists(key)) {
        // p. ej., el bucket se recreó: las filas son la fuente de verdad y el resumen las sigue
        LOG.warn("Summary of {} is missing; writing it again from the loaded rows", key);
        summary.rewrite(key);
      }
      return 0;
    }
  }

  /** La clave configurada, o el archivo del día hábil previo. */
  String inputKey() {
    String configured = properties.inputKey();
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    LocalDate today = LocalDate.now(clock.withZone(calendar.zone()));
    return SettlementFile.keyFor(properties.inputPrefix(), calendar.previousBusinessDay(today));
  }

  @Override
  public int getExitCode() {
    return exitCode.get();
  }
}
