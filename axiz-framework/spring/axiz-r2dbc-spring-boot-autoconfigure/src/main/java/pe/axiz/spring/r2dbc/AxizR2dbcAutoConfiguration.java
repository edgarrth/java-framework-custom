package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;
import org.springframework.r2dbc.core.DatabaseClient;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.kit.r2dbc.ReactiveSql;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;

/**
 * Persistencia relacional reactiva sobre Aurora PostgreSQL: un {@link ReactiveSql} que se une a la
 * transacción reactiva del invocante, los converters de columnas de axiz ({@code EncryptedText},
 * {@code Money}) para Spring Data R2DBC y un endpoint de lectura opcional. El pool de conexiones,
 * el gestor de transacciones y las observaciones de R2DBC provienen de Spring Boot; Flyway migra
 * sobre JDBC.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
      "pe.axiz.spring.r2dbc.AxizR2dbcPasswordAutoConfiguration"
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
  @ConditionalOnConfiguredProperty("axiz.r2dbc.reader.url")
  ReaderDatabase axizReaderDatabase(
      AxizR2dbcProperties p,
      Environment env,
      ObjectProvider<ConnectionFactoryDecorator> decorators,
      ObjectProvider<Clock> clock,
      ResourceLoader resources,
      ObjectProvider<DatabasePasswordProviderFactory> passwordFactories) {
    String url = env.getRequiredProperty("axiz.r2dbc.reader.url");
    String username = env.getProperty("spring.r2dbc.username");
    var factory = passwordFactories.getIfAvailable();
    var passwords =
        factory == null
            ? null
            : factory.create(
                url,
                Objects.requireNonNull(
                    username, "A reader with dynamic credentials requires spring.r2dbc.username"));
    try {
      return new ReaderDatabase(
          ReaderConnections.create(
              url,
              p.reader().maxSize(),
              username,
              env.getProperty("spring.r2dbc.password"),
              passwords,
              Binder.get(env)
                  .bind("spring.r2dbc.properties", Bindable.mapOf(String.class, String.class))
                  .orElseGet(Map::of),
              decorators.orderedStream().toList(),
              DataTransportSecurityPolicy.from(env::getProperty)),
          passwords instanceof AutoCloseable closeable ? closeable : null);
    } catch (RuntimeException | Error failure) {
      if (passwords instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception cleanup) {
          if (cleanup instanceof InterruptedException) Thread.currentThread().interrupt();
          if (cleanup != failure) failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  @Bean
  @ConditionalOnClass(name = "org.springframework.data.r2dbc.mapping.R2dbcMappingContext")
  EncryptionContextValidator axizR2dbcEncryptionContexts(
      ApplicationContext context, AxizR2dbcProperties properties) {
    return new EncryptionContextValidator(context, properties.encryptionContext());
  }

  /** Tipos de columna de axiz para entidades de Spring Data R2DBC. */
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
              : AxizR2dbcConverters.all(encryptor::getObject, properties.encryptionContext()));
    }
  }
}
