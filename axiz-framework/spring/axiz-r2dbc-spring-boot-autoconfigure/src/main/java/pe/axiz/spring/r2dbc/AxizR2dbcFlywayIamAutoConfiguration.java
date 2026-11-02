package pe.axiz.spring.r2dbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/** With IAM authentication, Flyway connects over JDBC with a token of its own. */
@AutoConfiguration(
    after = AxizR2dbcIamAutoConfiguration.class,
    beforeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ConditionalOnClass(
    name = {
      "org.flywaydb.core.Flyway",
      "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer"
    })
@ConditionalOnBooleanProperty("axiz.r2dbc.iam.enabled")
@ConditionalOnBean(DatabasePasswordProvider.class)
public class AxizR2dbcFlywayIamAutoConfiguration {

  @Bean
  FlywayIamCustomizer axizFlywayIamCustomizer(DatabasePasswordProvider passwords, Environment env) {
    return new FlywayIamCustomizer(passwords, env);
  }
}
