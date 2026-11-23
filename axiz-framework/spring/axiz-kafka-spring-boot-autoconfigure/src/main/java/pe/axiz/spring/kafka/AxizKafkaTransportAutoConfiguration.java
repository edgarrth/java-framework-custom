package pe.axiz.spring.kafka;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/**
 * Comprueba las fábricas gestionadas después de los customizers de Boot y antes de que inicialicen
 * los clientes.
 */
// Spring instancia la configuración; los BeanPostProcessor de infraestructura deben ser estáticos.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@AutoConfiguration(
    beforeName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
public class AxizKafkaTransportAutoConfiguration {
  @Bean
  static BeanPostProcessor axizKafkaTransportGuard(Environment environment) {
    return new Guard(environment);
  }

  private record Guard(Environment environment) implements BeanPostProcessor, Ordered {
    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
      var policy = DataTransportSecurityPolicy.from(environment::getProperty);
      if (bean instanceof ProducerFactory<?, ?> producer)
        KafkaTransportSecurity.validate(producer.getConfigurationProperties(), policy);
      if (bean instanceof ConsumerFactory<?, ?> consumer)
        KafkaTransportSecurity.validate(consumer.getConfigurationProperties(), policy);
      if (bean instanceof KafkaAdmin admin)
        KafkaTransportSecurity.validate(admin.getConfigurationProperties(), policy);
      return bean;
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
