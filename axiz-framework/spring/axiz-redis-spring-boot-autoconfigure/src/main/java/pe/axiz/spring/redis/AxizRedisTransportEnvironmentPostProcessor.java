package pe.axiz.spring.redis;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Secure transport defaults at lowest precedence; the guard checks actual client options. */
public final class AxizRedisTransportEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {
  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    String source = "axizRedisTransportDefaults";
    environment.getPropertySources().remove(source);
    environment
        .getPropertySources()
        .addLast(new MapPropertySource(source, Map.of("spring.data.redis.ssl.enabled", "true")));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
