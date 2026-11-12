package pe.axiz.spring.jpa;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import pe.axiz.kit.jpa.AuditorProvider;
import pe.axiz.spring.foundation.aws.AwsIdentity;

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
  AuditingRegistrar axizAuditingRegistrar(AuditorProvider auditor, ObjectProvider<Clock> clock) {
    return new AuditingRegistrar(auditor, clock.getIfAvailable(Clock::systemUTC));
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "com.zaxxer.hikari.HikariDataSource",
        "software.amazon.jdbc.Driver",
        "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider"
      })
  @ConditionalOnProperty(
      prefix = "axiz.jpa.aws-wrapper",
      name = {"enabled", "iam"},
      havingValue = "true")
  static class IamConfiguration {
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BeanPostProcessor axizJdbcIamCredentials(AwsIdentity identity) {
      return new JdbcIamCredentials(identity);
    }
  }
}
