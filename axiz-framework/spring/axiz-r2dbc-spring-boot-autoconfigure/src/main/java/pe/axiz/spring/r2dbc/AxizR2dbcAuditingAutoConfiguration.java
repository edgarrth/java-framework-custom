package pe.axiz.spring.r2dbc;

import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.ReactiveAuditorAware;
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * Auditing of {@link AuditableRow} (and any entity with {@code @CreatedBy}/{@code @CreatedDate}
 * fields): the caller of the request and the application clock, in UTC. Skipped when the service
 * enables R2DBC auditing itself.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration")
@ConditionalOnClass(EnableR2dbcAuditing.class)
@ConditionalOnBean(R2dbcMappingContext.class)
@ConditionalOnMissingBean(name = "r2dbcAuditingHandler")
@ConditionalOnBooleanProperty(name = "axiz.r2dbc.auditing.enabled", matchIfMissing = true)
@EnableR2dbcAuditing(
    auditorAwareRef = "axizR2dbcAuditor",
    dateTimeProviderRef = "axizR2dbcDateTimeProvider")
public class AxizR2dbcAuditingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(name = "axizR2dbcAuditor")
  ReactiveAuditorAware<String> axizR2dbcAuditor() {
    return new RequestReactiveAuditor();
  }

  @Bean
  DateTimeProvider axizR2dbcDateTimeProvider(ObjectProvider<Clock> clock) {
    Clock c = clock.getIfAvailable(Clock::systemUTC);
    return () -> Optional.of(c.instant());
  }
}
