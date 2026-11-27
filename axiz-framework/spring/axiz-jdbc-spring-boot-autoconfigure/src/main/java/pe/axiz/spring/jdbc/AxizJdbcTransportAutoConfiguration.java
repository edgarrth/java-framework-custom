package pe.axiz.spring.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.proxy.ProxyJdbcObject;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.util.ClassUtils;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;
import pe.axiz.kit.jdbc.JdbcTransportSecurity;

/**
 * Valida los pools JDBC gestionados y la conexión final de Flyway antes de su primer uso
 * gestionado.
 */
// Spring instancia la configuración; los BeanPostProcessor de infraestructura deben ser estáticos.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
    })
public class AxizJdbcTransportAutoConfiguration {
  @Bean
  static JdbcSources axizJdbcTransportGuard(
      Environment environment, ObjectProvider<JdbcDataSourceTlsValidator> validators) {
    return new JdbcSources(environment, validators);
  }

  static final class JdbcSources implements BeanPostProcessor, Ordered {
    private final Environment environment;
    private final ObjectProvider<JdbcDataSourceTlsValidator> validators;
    private final Set<DataSource> checked = Collections.newSetFromMap(new IdentityHashMap<>());

    JdbcSources(Environment environment, ObjectProvider<JdbcDataSourceTlsValidator> validators) {
      this.environment = environment;
      this.validators = validators;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
      if (bean instanceof DataSource source) validate(source, 0);
      return bean;
    }

    synchronized void validate(DataSource source, int depth) {
      if (depth > 8)
        throw new IllegalStateException("Cyclic or excessive JDBC DataSource wrapping");
      if (ClassUtils.isPresent(
              "net.ttddyy.dsproxy.proxy.ProxyJdbcObject", getClass().getClassLoader())
          && ObservedSource.supports(source)) {
        validate(ObservedSource.target(source), depth + 1);
        return;
      }
      var policy = DataTransportSecurityPolicy.from(environment::getProperty);
      if (ClassUtils.isPresent(
              "org.springframework.jdbc.datasource.SimpleDriverDataSource",
              getClass().getClassLoader())
          && SimpleSource.supports(source)) {
        SimpleSource.validate(source, policy);
        checked.add(source);
        return;
      }
      boolean hikari =
          ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource", getClass().getClassLoader());
      if (hikari && Hikari.supports(source)) {
        if (!checked.contains(source) && Hikari.running(source))
          throw new IllegalStateException("Validate JDBC transport before starting a custom pool");
        Hikari.validate(source, policy, this, depth);
        checked.add(source);
        return;
      }
      var validator =
          validators.orderedStream().filter(candidate -> candidate.supports(source)).findFirst();
      if (validator.isEmpty())
        throw new IllegalStateException(
            "Custom DataSource requires a JdbcDataSourceTlsValidator and pre-construction"
                + " validation: "
                + source.getClass().getName());
      validator.get().validate(source, policy);
      checked.add(source);
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }

  /** Datasource-micrometer decora el pool verificado después de la inicialización del bean. */
  private static final class ObservedSource {
    private ObservedSource() {}

    static boolean supports(DataSource source) {
      return source instanceof ProxyJdbcObject || source.getClass() == ProxyDataSource.class;
    }

    static DataSource target(DataSource source) {
      Object target =
          source instanceof ProxyJdbcObject proxy
              ? proxy.getTarget()
              : ((ProxyDataSource) source).getDataSource();
      if (!(target instanceof DataSource delegate))
        throw new IllegalStateException("JDBC observation proxy requires a DataSource target");
      return delegate;
    }
  }

  private static final class Hikari {
    private Hikari() {}

    static boolean supports(DataSource source) {
      return source instanceof HikariDataSource;
    }

    static boolean running(DataSource source) {
      return ((HikariDataSource) source).isRunning();
    }

    static void validate(
        DataSource source, DataTransportSecurityPolicy policy, JdbcSources owner, int depth) {
      var pool = (HikariDataSource) source;
      String url = pool.getJdbcUrl();
      if (url != null) JdbcTransportSecurity.validate(url, pool.getDataSourceProperties(), policy);
      else {
        DataSource delegate = pool.getDataSource();
        if (delegate == null)
          throw new IllegalStateException(
              "Managed JDBC pool requires effective connection settings");
        owner.validate(delegate, depth + 1);
      }
    }
  }

  /**
   * La fuente de migración separada de Boot es diferida y expone las opciones efectivas de su
   * driver.
   */
  private static final class SimpleSource {
    private SimpleSource() {}

    static boolean supports(DataSource source) {
      if (source.getClass() != SimpleDriverDataSource.class) return false;
      var driver = ((SimpleDriverDataSource) source).getDriver();
      return driver != null
          && Set.of("org.postgresql.Driver", "software.amazon.jdbc.Driver")
              .contains(driver.getClass().getName());
    }

    static void validate(DataSource source, DataTransportSecurityPolicy policy) {
      var simple = (SimpleDriverDataSource) source;
      String url = simple.getUrl();
      if (url == null)
        throw new IllegalStateException("JDBC source requires effective connection settings");
      Properties properties = simple.getConnectionProperties();
      JdbcTransportSecurity.validate(
          url, properties == null ? new Properties() : properties, policy);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.flywaydb.core.Flyway")
  static class Migrations {
    @Bean
    static BeanPostProcessor axizFlywayTransportGuard(
        Environment environment, JdbcSources sources) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String name) {
          if (bean instanceof Flyway flyway) {
            var config = flyway.getConfiguration();
            String url = config.getUrl();
            if (url != null) {
              var properties = new Properties();
              if (config.getJdbcProperties() != null) properties.putAll(config.getJdbcProperties());
              JdbcTransportSecurity.validate(
                  url, properties, DataTransportSecurityPolicy.from(environment::getProperty));
            } else {
              DataSource dataSource = config.getDataSource();
              if (dataSource == null)
                throw new IllegalStateException("Flyway requires effective connection settings");
              sources.validate(dataSource, 0);
            }
          }
          return bean;
        }
      };
    }
  }
}
