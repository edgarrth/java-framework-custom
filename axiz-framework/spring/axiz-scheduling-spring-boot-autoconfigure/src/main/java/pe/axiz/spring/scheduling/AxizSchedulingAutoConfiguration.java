package pe.axiz.spring.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} con ShedLock: las tareas se anotan con {@code @SchedulerLock(name = ...)} para
 * que solo una réplica las ejecute. Los tiempos de lock provienen del reloj de la base de datos.
 */
@AutoConfiguration(
    afterName = {
      "pe.axiz.spring.scheduling.jdbc.AxizSchedulingJdbcAutoConfiguration",
      "pe.axiz.spring.scheduling.r2dbc.AxizSchedulingR2dbcAutoConfiguration",
      "pe.axiz.spring.scheduling.dynamodb.AxizSchedulingDynamoDbAutoConfiguration"
    },
    // @EnableScheduling debe detectarse antes de que Boot decida si crea su TaskScheduler (hilos
    // virtuales, apagado ordenado); de lo contrario, Spring recurre a un scheduler privado de un
    // solo hilo ("No TaskScheduler/ScheduledExecutorService bean found")
    beforeName = "org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.scheduling.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSchedulingProperties.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${axiz.scheduling.default-lock-at-most-for:PT10M}")
public class AxizSchedulingAutoConfiguration {

  @Bean
  SmartInitializingSingleton axizSchedulingProviderCheck(ObjectProvider<LockProvider> providers) {
    return () -> {
      if (providers.getIfAvailable() == null)
        throw new IllegalStateException(
            "Scheduling requires its selected storage integration or a LockProvider bean");
    };
  }
}
