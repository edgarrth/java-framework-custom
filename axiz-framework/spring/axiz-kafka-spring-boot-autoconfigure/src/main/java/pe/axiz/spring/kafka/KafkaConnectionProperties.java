package pe.axiz.spring.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.core.env.Environment;

/**
 * Propiedades nativas de Kafka tomadas de la integración de Boot seleccionada; no se crea ningún
 * cliente.
 */
public final class KafkaConnectionProperties {
  private KafkaConnectionProperties() {}

  /**
   * Los ámbitos de cliente tienen configuraciones de conexión y TLS efectivas de forma
   * independiente.
   */
  public enum ClientKind {
    PRODUCER,
    CONSUMER,
    ADMIN
  }

  /**
   * Resuelve las propiedades nativas; los invocantes vuelven a validar después de sus propias
   * personalizaciones finales.
   */
  public static Map<String, Object> properties(
      Environment environment, @Nullable KafkaConnectionDetails details, ClientKind kind) {
    var properties = Binder.get(environment).bindOrCreate("spring.kafka", KafkaProperties.class);
    Map<String, Object> result =
        new HashMap<>(
            switch (kind) {
              case PRODUCER -> properties.buildProducerProperties();
              case CONSUMER -> properties.buildConsumerProperties();
              case ADMIN -> properties.buildAdminProperties();
            });
    if (details != null) {
      var connection =
          switch (kind) {
            case PRODUCER -> details.getProducer();
            case CONSUMER -> details.getConsumer();
            case ADMIN -> details.getAdmin();
          };
      result.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, connection.getBootstrapServers());
      String protocol = connection.getSecurityProtocol();
      if (protocol != null) result.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
      SslBundle bundle = connection.getSslBundle();
      if (bundle != null) {
        result.put(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, SslBundleSslEngineFactory.class);
        result.put(SslBundle.class.getName(), bundle);
      }
    }
    return result;
  }
}
