package pe.axiz.spring.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.ssl.DefaultSslEngineFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

class KafkaTransportSecurityTest {
  private final DataTransportSecurityPolicy production =
      new DataTransportSecurityPolicy("prod", true);

  @Test
  void opaqueSslEnginesCannotClaimNativePeerValidation() {
    assertThatThrownBy(
            () ->
                KafkaTransportSecurity.validate(
                    Map.of(
                        "security.protocol",
                        "SSL",
                        SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG,
                        "example.TrustAllSslEngineFactory"),
                    production))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(
            () ->
                KafkaTransportSecurity.validate(
                    Map.of(
                        "security.protocol",
                        "SSL",
                        SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG,
                        "org.apache.kafka.common.security.ssl.DefaultSslEngineFactory"),
                    production))
        .doesNotThrowAnyException();
  }

  @Test
  void nativeDefaultsAndFinalOverridesRequireEncryptionAndVerifiedPeers() {
    for (String protocol : List.of("PLAINTEXT", "SASL_PLAINTEXT"))
      assertThatThrownBy(
              () ->
                  KafkaTransportSecurity.validate(
                      Map.of("security.protocol", protocol), production))
          .isInstanceOf(IllegalStateException.class);
    for (String protocol : List.of("SSL", "SASL_SSL")) {
      assertThatCode(
              () ->
                  KafkaTransportSecurity.validate(
                      Map.of("security.protocol", protocol), production))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () ->
                  KafkaTransportSecurity.validate(
                      Map.of(
                          "security.protocol",
                          protocol,
                          "ssl.endpoint.identification.algorithm",
                          ""),
                      production))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void scopesAndConnectionDetailsAreTheActualInputsIncludingSslBundles() {
    var env =
        new MockEnvironment()
            .withProperty("spring.kafka.bootstrap-servers", "property-broker:9093")
            .withProperty("spring.kafka.security.protocol", "SSL")
            .withProperty("spring.kafka.consumer.security.protocol", "SASL_SSL")
            .withProperty(
                "spring.kafka.consumer.properties.ssl.endpoint.identification.algorithm", "");
    var consumer =
        KafkaConnectionProperties.properties(
            env, null, KafkaConnectionProperties.ClientKind.CONSUMER);
    assertThat(consumer).containsEntry("security.protocol", "SASL_SSL");
    assertThatThrownBy(() -> KafkaTransportSecurity.validate(consumer, production))
        .isInstanceOf(IllegalStateException.class);
    SslBundle bundle = org.mockito.Mockito.mock(SslBundle.class);
    var details =
        new KafkaConnectionDetails() {
          @Override
          public List<String> getBootstrapServers() {
            return List.of("details-broker:9093");
          }

          @Override
          public String getSecurityProtocol() {
            return "SSL";
          }

          @Override
          public SslBundle getSslBundle() {
            return bundle;
          }
        };
    var actual =
        KafkaConnectionProperties.properties(
            env, details, KafkaConnectionProperties.ClientKind.PRODUCER);
    assertThat(actual)
        .containsEntry("bootstrap.servers", List.of("details-broker:9093"))
        .containsEntry(SslBundle.class.getName(), bundle)
        .containsKey(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG);
    assertThatCode(() -> KafkaTransportSecurity.validate(actual, production))
        .doesNotThrowAnyException();
  }

  @Test
  void everyClientScopeUsesItsOwnOverridesAndConnectionDetailsTakePrecedence() {
    var env =
        new MockEnvironment()
            .withProperty("spring.kafka.security.protocol", "SSL")
            .withProperty("spring.kafka.bootstrap-servers", "ignored.test:9093")
            .withProperty("spring.kafka.producer.properties.client.id", "producer-proof")
            .withProperty("spring.kafka.consumer.properties.client.id", "consumer-proof")
            .withProperty("spring.kafka.admin.properties.client.id", "admin-proof");
    var supplied =
        new KafkaConnectionDetails() {
          @Override
          public List<String> getBootstrapServers() {
            return List.of("details.test:9093");
          }
        };
    for (var kind : KafkaConnectionProperties.ClientKind.values()) {
      var defaults = KafkaConnectionProperties.properties(env, null, kind);
      assertThat(defaults)
          .containsEntry("client.id", kind.name().toLowerCase(Locale.ROOT) + "-proof");
      assertThatCode(() -> KafkaTransportSecurity.validate(defaults, production))
          .doesNotThrowAnyException();
      var actual = KafkaConnectionProperties.properties(env, supplied, kind);
      assertThat(actual)
          .containsEntry("bootstrap.servers", List.of("details.test:9093"))
          .containsEntry("security.protocol", "SSL")
          .doesNotContainKey(SslBundle.class.getName());
    }
  }

  @Test
  void everyManagedClientValidatesItsFinalSettingsBeforeInitializingNativeClients() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var guard = AxizKafkaTransportAutoConfiguration.axizKafkaTransportGuard(env);
    var consumer =
        new DefaultKafkaConsumerFactory<>(Map.<String, Object>of("security.protocol", "SSL"));
    var admin = new KafkaAdmin(Map.of("security.protocol", "SASL_SSL"));
    assertThat(guard.postProcessBeforeInitialization(consumer, "consumer")).isSameAs(consumer);
    assertThat(guard.postProcessBeforeInitialization(admin, "admin")).isSameAs(admin);
    consumer.updateConfigs(Map.of("ssl.endpoint.identification.algorithm", ""));
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(consumer, "consumer"))
        .isInstanceOf(IllegalStateException.class);
    var unsafeAdmin =
        new KafkaAdmin(
            Map.of("security.protocol", "SSL", "ssl.endpoint.identification.algorithm", ""));
    assertThatThrownBy(() -> guard.postProcessBeforeInitialization(unsafeAdmin, "admin"))
        .isInstanceOf(IllegalStateException.class);
    var other = new Object();
    assertThat(guard.postProcessBeforeInitialization(other, "other")).isSameAs(other);
    assertThatCode(
            () ->
                KafkaTransportSecurity.validate(
                    Map.of(
                        "security.protocol",
                        "SSL",
                        SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG,
                        DefaultSslEngineFactory.class),
                    production))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> KafkaTransportSecurity.validate(Map.of(), production))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void finalManagedFactoryIsRejectedBeforeAnyProducerIsCreated() {
    var env = new MockEnvironment().withProperty("axiz.config.environment", "prod");
    var factory = new DefaultKafkaProducerFactory<>(Map.of("security.protocol", "SSL"));
    factory.updateConfigs(Map.of("ssl.endpoint.identification.algorithm", ""));
    assertThatThrownBy(
            () ->
                AxizKafkaTransportAutoConfiguration.axizKafkaTransportGuard(env)
                    .postProcessBeforeInitialization(factory, "producerFactory"))
        .isInstanceOf(IllegalStateException.class);
    factory.destroy();
  }

  @Test
  void plaintextExceptionNeedsBothExplicitEnvironmentAndExplicitPermission() {
    var insecure = Map.<String, Object>of("security.protocol", "PLAINTEXT");
    for (String environment : List.of("local", "test", "ci")) {
      assertThatCode(
              () ->
                  KafkaTransportSecurity.validate(
                      insecure, new DataTransportSecurityPolicy(environment, true)))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () ->
                  KafkaTransportSecurity.validate(
                      insecure, new DataTransportSecurityPolicy(environment, false)))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(
            () ->
                KafkaTransportSecurity.validate(
                    insecure, new DataTransportSecurityPolicy(null, true)))
        .isInstanceOf(IllegalStateException.class);
  }
}
