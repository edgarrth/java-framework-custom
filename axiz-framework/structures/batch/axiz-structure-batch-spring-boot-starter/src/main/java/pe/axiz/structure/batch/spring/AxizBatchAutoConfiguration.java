package pe.axiz.structure.batch.spring;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.metrics.EventMetrics;

/**
 * Estilo batch sobre Spring Batch: cada job informa métricas, auditoría y una línea de resumen; los
 * lectores de archivos en S3 reinician desde el último chunk confirmado. Se ejecuta como CronJob de
 * Kubernetes (código de salida según el estado del job) o desde {@code @Scheduled} con ShedLock.
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
