package pe.axiz.spring.scheduling.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.spring.scheduling.AxizSchedulingAutoConfiguration;
import pe.axiz.spring.scheduling.AxizSchedulingProperties;

/**
 * ShedLock on Aurora over R2DBC ({@code axiz.scheduling.store=r2dbc}) for reactive services; the
 * lock table comes from the service's Flyway migrations.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
    before = AxizSchedulingAutoConfiguration.class)
@ConditionalOnClass(name = "net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider")
@ConditionalOnProperty(name = "axiz.scheduling.store", havingValue = "r2dbc")
@ConditionalOnBean(ConnectionFactory.class)
@ConditionalOnBooleanProperty(name = "axiz.scheduling.enabled", matchIfMissing = true)
@EnableConfigurationProperties({
  AxizSchedulingR2dbcProperties.class,
  AxizSchedulingProperties.class
})
public class AxizSchedulingR2dbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  LockProvider axizR2dbcLockProvider(
      ConnectionFactory connectionFactory, AxizSchedulingR2dbcProperties p) {
    return new R2dbcLockProvider(connectionFactory, p.table());
  }
}
