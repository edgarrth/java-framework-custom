package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** Valkey (protocolo Redis) para caché, idempotencia, locks y límites de tasa. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfiguration {

  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> axizRedisContainer() {
    return AxizContainers.valkey();
  }

  @Bean
  DynamicPropertyRegistrar axizRedisTransport() {
    return registry -> registry.add("spring.data.redis.ssl.enabled", () -> "false");
  }
}
