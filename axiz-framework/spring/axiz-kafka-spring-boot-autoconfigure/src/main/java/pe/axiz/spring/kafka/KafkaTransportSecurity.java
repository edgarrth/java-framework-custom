package pe.axiz.spring.kafka;

import java.util.Locale;
import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/** Valida el transporte nativo de Kafka sin requerir Boot Kafka ni crear un cliente. */
public final class KafkaTransportSecurity {
  private KafkaTransportSecurity() {}

  /**
   * Comprueba las opciones exactas que están por pasarse al productor, consumidor o admin nativo.
   */
  public static void validate(Map<String, ?> properties, DataTransportSecurityPolicy policy) {
    Object protocolValue = properties.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
    String protocol =
        protocolValue == null ? "PLAINTEXT" : protocolValue.toString().toUpperCase(Locale.ROOT);
    Object algorithmValue = properties.get(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG);
    String algorithm =
        algorithmValue == null ? "HTTPS" : algorithmValue.toString().toUpperCase(Locale.ROOT);
    Object factory = properties.get(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG);
    String factoryName =
        factory instanceof Class<?> type
            ? type.getName()
            : factory == null ? "" : factory.toString();
    boolean verifiedEngine =
        factory == null
            || factoryName.equals("org.apache.kafka.common.security.ssl.DefaultSslEngineFactory")
            || factoryName.equals(
                "org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory");
    policy.requireVerified(
        protocol.equals("SSL") || protocol.equals("SASL_SSL"),
        verifiedEngine && (algorithm.equals("HTTPS") || algorithm.equals("LDAPS")),
        "Kafka");
  }
}
