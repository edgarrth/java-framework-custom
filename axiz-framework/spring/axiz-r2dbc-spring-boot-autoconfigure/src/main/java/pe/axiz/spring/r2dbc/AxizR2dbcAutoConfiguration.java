package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.kit.r2dbc.ReactiveSql;

/**
 * Reactive relational persistence on Aurora PostgreSQL: a {@link ReactiveSql} that joins the
 * reactive transaction of the caller, the axiz column converters ({@code EncryptedText}, {@code
 * Money}) for Spring Data R2DBC, and an optional reader endpoint. The connection pool, the
 * transaction manager and the R2DBC observations come from Spring Boot; Flyway migrates over JDBC.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
      "pe.axiz.spring.r2dbc.AxizR2dbcIamAutoConfiguration"
    },
    beforeName = "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration")
@ConditionalOnClass({ConnectionFactory.class, DatabaseClient.class})
@EnableConfigurationProperties(AxizR2dbcProperties.class)
public class AxizR2dbcAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnSingleCandidate(DatabaseClient.class)
  ReactiveSql axizReactiveSql(DatabaseClient client) {
    return new DatabaseClientReactiveSql(client);
  }

  @Bean
  @ConditionalOnProperty("axiz.r2dbc.reader.url")
  ReaderDatabase axizReaderDatabase(
      AxizR2dbcProperties p,
      Environment env,
      ObjectProvider<ConnectionFactoryDecorator> decorators,
      ObjectProvider<Clock> clock) {
    String url = env.getRequiredProperty("axiz.r2dbc.reader.url");
    String username = env.getProperty("spring.r2dbc.username");
    var iam =
        p.iam().enabled() && username != null
            ? IamTokens.provider(
                url,
                username,
                p.iam(),
                env.getProperty("axiz.aws.region"),
                clock.getIfAvailable(Clock::systemUTC))
            : null;
    return new ReaderDatabase(
        ReaderConnections.create(
            url,
            p.reader().maxSize(),
            username,
            env.getProperty("spring.r2dbc.password"),
            iam,
            Binder.get(env)
                .bind("spring.r2dbc.properties", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of),
            decorators.orderedStream().toList()),
        iam);
  }

  @Bean
  EncryptionContextValidator axizR2dbcEncryptionContexts(
      ApplicationContext context, AxizR2dbcProperties properties) {
    return new EncryptionContextValidator(context, properties.encryptionContext());
  }

  /** axiz column types for Spring Data R2DBC entities. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.data.r2dbc.convert.R2dbcCustomConversions")
  static class ConversionsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(DatabaseClient.class)
    R2dbcCustomConversions r2dbcCustomConversions(
        DatabaseClient client,
        ObjectProvider<FieldEncryptor> encryptor,
        AxizR2dbcProperties properties) {
      return R2dbcCustomConversions.of(
          DialectResolver.getDialect(client.getConnectionFactory()),
          properties.encryptionContext() == null
              ? AxizR2dbcConverters.money()
              : AxizR2dbcConverters.all(
                  () -> {
                    FieldEncryptor bean = encryptor.getIfAvailable();
                    return bean != null ? bean : EncryptionRegistry.encryptor();
                  },
                  properties.encryptionContext()));
    }
  }
}
