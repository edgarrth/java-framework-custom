package pe.axiz.spring.jpa;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.persistence.jpa.AuditorProvider;

/**
 * Relational persistence conventions: auditing of {@code AuditableEntity} from the authenticated
 * caller, UTC timestamps from the application clock. Base entities, soft delete and optimistic
 * locking live in the entity classes; schema changes go through Flyway.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
public class AxizJpaAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  AuditorProvider axizAuditorProvider() {
    return new RequestAuditorProvider();
  }

  @Bean
  AuditingRegistrar axizAuditingRegistrar(AuditorProvider auditor, ObjectProvider<Clock> clock) {
    return new AuditingRegistrar(auditor, clock.getIfAvailable(Clock::systemUTC));
  }
}
