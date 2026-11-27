package pe.axiz.spring.jpa.aws;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import pe.axiz.spring.aws.AwsIdentity;

/** Posee los ámbitos de credenciales IAM por pool del wrapper JDBC. */
@AutoConfiguration(
    beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
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
public class AxizJpaAwsAutoConfiguration {
  @Bean
  @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
  static BeanPostProcessor axizJdbcIamCredentials(AwsIdentity identity) {
    return new JdbcIamCredentials(identity);
  }
}
