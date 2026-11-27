package pe.axiz.payments.process.adapter.in.scheduling;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.axiz.core.saga.SagaRecovery;

/**
 * Reanuda las sagas de pago interrumpidas por una caída o un redespliegue (inactivas por más de dos
 * minutos). ShedLock hace que solo una réplica lo ejecute a la vez.
 */
@Component
public class SagaRecoveryJob {

  private static final Logger LOG = LoggerFactory.getLogger(SagaRecoveryJob.class);
  static final Duration IDLE = Duration.ofMinutes(2);
  static final int BATCH = 50;

  private final SagaRecovery recovery;

  public SagaRecoveryJob(SagaRecovery recovery) {
    this.recovery = recovery;
  }

  @Scheduled(fixedDelayString = "${payments.process.recovery-interval:60s}")
  @SchedulerLock(name = "payment-saga-recovery", lockAtMostFor = "5m")
  public void resumeStale() {
    int resumed = recovery.resumeStale(IDLE, BATCH);
    if (resumed > 0) {
      LOG.info("Resumed {} interrupted payment processes", resumed);
    }
  }
}
