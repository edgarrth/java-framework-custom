package pe.axiz.spring.r2dbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/** Con autenticación IAM, Flyway se conecta sobre JDBC con un token propio. */
@AutoConfiguration(
    after = AxizR2dbcPasswordAutoConfiguration.class,
    beforeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ConditionalOnClass(
    name = {
      "org.flywaydb.core.Flyway",
      "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer"
    })
@ConditionalOnBean(DatabasePasswordProvider.class)
public class AxizR2dbcFlywayPasswordAutoConfiguration {

  @Bean
  FlywayPasswordCustomizer axizFlywayPasswordCustomizer(
      DatabasePasswordProvider passwords, Environment env) {
    return new FlywayPasswordCustomizer(passwords, env);
  }
}
