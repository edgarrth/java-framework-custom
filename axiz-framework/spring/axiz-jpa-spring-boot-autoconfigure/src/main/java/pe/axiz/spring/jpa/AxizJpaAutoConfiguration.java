package pe.axiz.spring.jpa;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import pe.axiz.kit.jpa.AuditListener;
import pe.axiz.kit.jpa.AuditorProvider;

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
  EncryptionContextValidator axizJpaEncryptionContexts(ApplicationContext context) {
    return new EncryptionContextValidator(context);
  }

  @Bean
  @ConditionalOnMissingBean
  AuditorProvider axizAuditorProvider() {
    return new RequestAuditorProvider();
  }

  @Bean
  @ConditionalOnMissingBean
  AuditListener axizAuditListener(AuditorProvider auditor, ObjectProvider<Clock> clock) {
    return new AuditListener(auditor, clock.getIfAvailable(Clock::systemUTC));
  }
}
