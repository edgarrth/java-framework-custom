package pe.axiz.spring.redis;

import io.lettuce.core.SslVerifyMode;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/** Validates final Lettuce configuration before its factory initializes native clients. */
// Spring instantiates the configuration; infrastructure BeanPostProcessors must be static.
@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
@AutoConfiguration(
    beforeName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(
    name = "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory")
public class AxizRedisTransportAutoConfiguration {
  @Bean
  static BeanPostProcessor axizRedisTransportGuard(Environment environment) {
    return new Guard(environment);
  }

  private record Guard(Environment environment) implements BeanPostProcessor, Ordered {
    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
      if (bean instanceof LettuceConnectionFactory factory) {
        if (factory.isRunning())
          throw new IllegalStateException(
              "Validate Redis transport before starting a custom factory");
        var settings = factory.getClientConfiguration();
        DataTransportSecurityPolicy.from(environment::getProperty)
            .requireVerified(
                settings.isUseSsl(),
                settings.getVerifyMode() == SslVerifyMode.FULL,
                "Redis Lettuce");
      }
      return bean;
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
