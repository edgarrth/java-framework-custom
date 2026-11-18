package pe.axiz.spring.crypto;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.ClassUtils;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.KeyProvider;
import pe.axiz.core.crypto.PasswordHasher;
import pe.axiz.core.crypto.Pbkdf2PasswordHasher;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.spring.foundation.ConditionalOnConfiguredProperty;

/**
 * Field encryption with keys from the secret store, blind indexes, and password hashing (Argon2id
 * by default). Only AEAD and modern schemes are offered.
 */
@AutoConfiguration(afterName = "pe.axiz.spring.secrets.AxizSecretsAutoConfiguration")
@EnableConfigurationProperties(AxizCryptoProperties.class)
public class AxizCryptoAutoConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(AxizCryptoAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  PasswordHasher axizPasswordHasher(AxizCryptoProperties p) {
    String algorithm = p.password().algorithm();
    boolean springCrypto =
        ClassUtils.isPresent(
            "org.springframework.security.crypto.password.PasswordEncoder",
            AxizCryptoAutoConfiguration.class.getClassLoader());
    return switch (algorithm) {
      case "pbkdf2" -> new Pbkdf2PasswordHasher(Pbkdf2PasswordHasher.DEFAULT_ITERATIONS);
      case "bcrypt" -> {
        if (!springCrypto) {
          throw new IllegalStateException("bcrypt requires spring-security-crypto");
        }
        yield SpringEncoders.bcrypt();
      }
      case "argon2id" -> {
        if (springCrypto && SpringEncoders.argon2Available()) {
          yield SpringEncoders.argon2();
        }
        LOG.warn(
            "axiz.crypto.password.algorithm=argon2id needs spring-security-crypto and"
                + " bcprov-jdk18on on the classpath; hashing with PBKDF2-HMAC-SHA256 instead");
        yield new Pbkdf2PasswordHasher(Pbkdf2PasswordHasher.DEFAULT_ITERATIONS);
      }
      default -> throw new IllegalStateException("Unsupported password algorithm: " + algorithm);
    };
  }

  /** Field encryption, active when a current key id is configured. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnConfiguredProperty("axiz.crypto.fields.current-key-id")
  @ConditionalOnBean(SecretResolver.class)
  static class FieldsConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(KeyProvider.class)
    StaticKeyProvider axizKeyProvider(AxizCryptoProperties p, SecretResolver secrets) {
      AxizCryptoProperties.Fields f = p.fields();
      String current = Objects.requireNonNull(f.currentKeyId());
      Map<String, byte[]> keys = new LinkedHashMap<>();
      List<String> ids =
          f.keyIds().contains(current)
              ? f.keyIds()
              : Stream.concat(f.keyIds().stream(), Stream.of(current)).toList();
      try {
        for (String id : ids) {
          try (var secret = secrets.require(f.secretPrefix() + id)) {
            keys.put(id, secret.base64Bytes(32, 32));
          }
        }
        return StaticKeyProvider.fromBytes(current, keys);
      } finally {
        keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
      }
    }

    @Bean
    @ConditionalOnMissingBean
    FieldEncryptor axizFieldEncryptor(KeyProvider keys) {
      return new FieldEncryptor(keys);
    }

    @Bean
    MeterBinder axizFieldEncryptionMetrics(
        AxizCryptoProperties properties, KeyProvider keys, FieldEncryptor encryptor) {
      List<String> ids =
          Stream.concat(properties.fields().keyIds().stream(), Stream.of(keys.currentKeyId()))
              .distinct()
              .toList();
      return registry ->
          ids.forEach(
              id ->
                  FunctionCounter.builder(
                          "axiz.crypto.field.encryptions",
                          encryptor,
                          value -> value.encryptions(id))
                      .tag("keyId", id)
                      .register(registry));
    }
  }

  /** Search keys do not require field encryption to be enabled. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnBean(SecretResolver.class)
  @ConditionalOnConfiguredProperty("axiz.crypto.fields.index-current-key-id")
  static class IndexConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    BlindIndex axizBlindIndex(
        AxizCryptoProperties properties,
        SecretResolver secrets,
        ObjectProvider<KeyProvider> providers) {
      Map<String, byte[]> keys = new LinkedHashMap<>();
      try {
        properties
            .fields()
            .indexSecrets()
            .forEach(
                (id, name) -> {
                  try (var value = secrets.require(name)) {
                    keys.put(id, value.base64Bytes(32, 1024));
                  }
                });
        KeyProvider provider = providers.getIfAvailable();
        if (provider instanceof StaticKeyProvider fieldKeys
            && keys.values().stream().anyMatch(fieldKeys::containsKeyMaterial))
          throw new IllegalStateException(
              "Field encryption and index keys must use different key material");
        return new BlindIndex(
            keys, Objects.requireNonNull(properties.fields().indexCurrentKeyId()));
      } finally {
        keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
      }
    }
  }

  /** Spring Security crypto encoders, isolated so the class is optional. */
  static final class SpringEncoders {

    private SpringEncoders() {}

    static boolean argon2Available() {
      return ClassUtils.isPresent(
          "org.bouncycastle.crypto.params.Argon2Parameters",
          AxizCryptoAutoConfiguration.class.getClassLoader());
    }

    static PasswordHasher argon2() {
      return new SpringPasswordHasher(Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());
    }

    static PasswordHasher bcrypt() {
      return new SpringPasswordHasher(new BCryptPasswordEncoder(12));
    }
  }
}
