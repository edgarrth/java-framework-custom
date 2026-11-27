package pe.axiz.spring.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;

/** Kafka para pruebas de mensajería; la autenticación IAM se desactiva para el broker local. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {

  @Bean
  @ServiceConnection
  KafkaContainer axizKafkaContainer() {
    return AxizContainers.kafka();
  }

  @Bean
  DynamicPropertyRegistrar axizKafkaProperties(KafkaContainer kafka) {
    return registry -> {
      registry.add("axiz.messaging.kafka.bootstrap-servers", kafka::getBootstrapServers);
      registry.add("axiz.messaging.kafka.authentication", () -> "configured");
      registry.add("axiz.messaging.kafka.iam", () -> "false");
    };
  }
}
