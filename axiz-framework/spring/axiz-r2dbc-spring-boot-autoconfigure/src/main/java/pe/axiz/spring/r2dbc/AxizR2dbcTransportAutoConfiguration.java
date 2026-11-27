package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.r2dbc.ConnectionFactoryDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/**
 * Protege las opciones R2DBC completadas antes del uso de la fábrica gestionada, con independencia
 * de IAM.
 */
@AutoConfiguration(
    beforeName = "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
@ConditionalOnClass(name = "io.r2dbc.spi.ConnectionFactory")
public class AxizR2dbcTransportAutoConfiguration {
  @Bean
  @Order(Ordered.LOWEST_PRECEDENCE)
  ConnectionFactoryDecorator axizR2dbcTransportValidation(Environment environment) {
    return factory -> {
      R2dbcTransportSecurity.validate(
          factory, DataTransportSecurityPolicy.from(environment::getProperty));
      return factory;
    };
  }

  @Bean
  static BeanPostProcessor axizR2dbcFactoryValidation(Environment environment) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof ConnectionFactory factory)
          R2dbcTransportSecurity.validate(
              factory, DataTransportSecurityPolicy.from(environment::getProperty));
        return bean;
      }
    };
  }
}
