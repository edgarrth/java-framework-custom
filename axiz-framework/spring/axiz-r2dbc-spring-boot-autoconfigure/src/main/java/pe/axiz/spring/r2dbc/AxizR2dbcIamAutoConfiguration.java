package pe.axiz.spring.r2dbc;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;

/**
 * IAM database authentication for Aurora ({@code axiz.r2dbc.iam.enabled=true}): every new
 * connection of the pool, and the Flyway migration at startup, authenticate with a short-lived
 * token signed with the pod's credentials. No database password exists in the secret store.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
@ConditionalOnClass(name = "io.r2dbc.spi.ConnectionFactory")
@ConditionalOnBooleanProperty("axiz.r2dbc.iam.enabled")
@EnableConfigurationProperties(AxizR2dbcProperties.class)
public class AxizR2dbcIamAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DatabasePasswordProvider axizDatabasePasswordProvider(
      Environment env,
      AxizR2dbcProperties p,
      ObjectProvider<Clock> clock,
      ResourceLoader resources) {
    return IamTokens.provider(
        env.getRequiredProperty("spring.r2dbc.url"),
        env.getRequiredProperty("spring.r2dbc.username"),
        p.iam(),
        env.getProperty("axiz.aws.region"),
        clock.getIfAvailable(Clock::systemUTC),
        resources.getClassLoader());
  }

  @Bean
  ConnectionFactoryOptionsBuilderCustomizer axizR2dbcIamPassword(
      DatabasePasswordProvider passwords) {
    return options -> IamTokens.apply(options, passwords);
  }
}
