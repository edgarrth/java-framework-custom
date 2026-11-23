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
 * Convenciones de persistencia relacional: auditoría de {@code AuditableEntity} a partir del
 * invocante autenticado y marcas de tiempo UTC del reloj de la aplicación. Las entidades base, el
 * borrado lógico y el bloqueo optimista residen en las clases de entidad; los cambios de esquema
 * pasan por Flyway.
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
