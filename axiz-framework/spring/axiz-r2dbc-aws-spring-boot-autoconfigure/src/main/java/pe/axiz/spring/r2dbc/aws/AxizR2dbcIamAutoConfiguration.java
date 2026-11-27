package pe.axiz.spring.r2dbc.aws;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.aws.AwsIdentity;
import pe.axiz.spring.r2dbc.DatabasePasswordProviderFactory;

/**
 * Autenticación de base de datos IAM para Aurora ({@code axiz.r2dbc.iam.enabled=true}): cada
 * conexión nueva del pool, y la migración Flyway en el arranque, se autentican con un token de vida
 * corta firmado con las credenciales del pod. No existe ninguna contraseña de base de datos en el
 * almacén de secretos.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
@ConditionalOnClass(name = "io.r2dbc.spi.ConnectionFactory")
@ConditionalOnBooleanProperty("axiz.r2dbc.iam.enabled")
@EnableConfigurationProperties(AxizR2dbcAwsProperties.class)
public class AxizR2dbcIamAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  DatabasePasswordProviderFactory axizRdsPasswordFactory(
      Environment env,
      AxizR2dbcAwsProperties p,
      ObjectProvider<Clock> clock,
      ResourceLoader resources,
      AwsIdentity identity) {
    return (url, username) ->
        IamTokens.provider(
            url,
            username,
            p,
            env.getProperty("axiz.aws.region"),
            clock.getIfAvailable(Clock::systemUTC),
            resources.getClassLoader(),
            identity);
  }

  @Bean
  @ConditionalOnMissingBean
  DatabasePasswordProvider axizDatabasePasswordProvider(
      Environment env, DatabasePasswordProviderFactory factory) {
    return factory.create(
        env.getRequiredProperty("spring.r2dbc.url"),
        env.getRequiredProperty("spring.r2dbc.username"));
  }
}
