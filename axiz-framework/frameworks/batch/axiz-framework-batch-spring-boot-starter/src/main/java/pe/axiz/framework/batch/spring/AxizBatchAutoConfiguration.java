package pe.axiz.framework.batch.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.metrics.EventMetrics;

/**
 * Batch style on Spring Batch: every job reports metrics, audit and a summary line; readers for
 * files in S3 restart from the last committed chunk. Runs as a Kubernetes CronJob (exit code from
 * the job status) or from {@code @Scheduled} with ShedLock.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.metrics.AxizMetricsAutoConfiguration",
      "pe.axiz.spring.audit.AxizAuditAutoConfiguration"
    })
@ConditionalOnClass(name = "org.springframework.batch.core.job.Job")
public class AxizBatchAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ObservedJobListener axizObservedJobListener(
      ObjectProvider<EventMetrics> metrics, ObjectProvider<AuditTrail> audit) {
    return new ObservedJobListener(metrics.getIfAvailable(), audit.getIfAvailable());
  }

  @Bean
  static ObservedJobListenerPostProcessor axizObservedJobListenerPostProcessor(
      ObjectProvider<ObservedJobListener> listener) {
    return new ObservedJobListenerPostProcessor(listener);
  }
}
