package pe.axiz.spring.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.axiz.spring.foundation.jdbc.SchemaInitializer;

/**
 * {@code @Scheduled} with ShedLock: annotate tasks with {@code @SchedulerLock(name = ...)} so only
 * one replica runs them. Lock times come from the database clock.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "pe.axiz.spring.scheduling.AxizSchedulingDynamoDbAutoConfiguration"
    },
    // @EnableScheduling must be seen before Boot decides whether to create its TaskScheduler
    // (virtual threads, graceful shutdown); otherwise Spring falls back to a private
    // single-thread scheduler ("No TaskScheduler/ScheduledExecutorService bean found")
    beforeName = "org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.scheduling.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSchedulingProperties.class)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${axiz.scheduling.default-lock-at-most-for:PT10M}")
public class AxizSchedulingAutoConfiguration {

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = "net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider")
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnProperty(
      name = "axiz.scheduling.store",
      havingValue = "jdbc",
      matchIfMissing = true)
  static class JdbcConfiguration {

    @Bean
    SchemaInitializer axizShedlockSchema(DataSource dataSource, AxizSchedulingProperties p) {
      return new SchemaInitializer(dataSource, "shedlock", p.initializeSchema());
    }

    @Bean
    @ConditionalOnMissingBean
    LockProvider axizJdbcLockProvider(
        DataSource dataSource, AxizSchedulingProperties p, SchemaInitializer axizShedlockSchema) {
      return new JdbcTemplateLockProvider(
          JdbcTemplateLockProvider.Configuration.builder()
              .withJdbcTemplate(new JdbcTemplate(dataSource))
              .withTableName(p.table())
              .usingDbTime()
              .build());
    }
  }
}
