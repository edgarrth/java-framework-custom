package pe.axiz.spring.crypto;

import java.util.Base64;
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
import pe.axiz.core.secrets.SecretNotFoundException;
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

    @Bean
    @ConditionalOnMissingBean
    KeyProvider axizKeyProvider(AxizCryptoProperties p, SecretResolver secrets) {
      AxizCryptoProperties.Fields f = p.fields();
      String current = Objects.requireNonNull(f.currentKeyId());
      Map<String, String> keys = new LinkedHashMap<>();
      List<String> ids =
          f.keyIds().contains(current)
              ? f.keyIds()
              : Stream.concat(f.keyIds().stream(), Stream.of(current)).toList();
      for (String id : ids) {
        String name = f.secretPrefix() + id;
        keys.put(
            id,
            secrets.resolve(name).orElseThrow(() -> new SecretNotFoundException(name)).reveal());
      }
      return StaticKeyProvider.fromBase64(current, keys);
    }

    @Bean
    @ConditionalOnMissingBean
    FieldEncryptor axizFieldEncryptor(KeyProvider keys) {
      return new FieldEncryptor(keys);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnConfiguredProperty("axiz.crypto.fields.blind-index-secret")
    BlindIndex axizBlindIndex(AxizCryptoProperties p, SecretResolver secrets) {
      String name = Objects.requireNonNull(p.fields().blindIndexSecret());
      byte[] key =
          Base64.getDecoder()
              .decode(
                  secrets
                      .resolve(name)
                      .orElseThrow(() -> new SecretNotFoundException(name))
                      .reveal());
      return new BlindIndex(key, p.fields().blindIndexBytes());
    }

    @Bean
    EncryptionRegistrar axizEncryptionRegistrar(
        FieldEncryptor encryptor, ObjectProvider<BlindIndex> index) {
      return new EncryptionRegistrar(encryptor, index.getIfAvailable());
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
