package pe.axiz.spring.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.PasswordHasher;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;
import software.amazon.awssdk.services.kms.KmsClient;

/** Key material comes only from the secret store; hashing and envelope encryption are wired. */
class CryptoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizCryptoAutoConfiguration.class));

  private static String newKey() {
    return Base64.getEncoder().encodeToString(AesGcm.newKey().getEncoded());
  }

  private static SecretResolver store(Map<String, String> secrets) {
    return name -> Optional.ofNullable(secrets.get(name)).map(SecretValue::of);
  }

  @Test
  void theDefaultHasherIsArgon2id() {
    runner.run(
        ctx -> {
          PasswordHasher hasher = ctx.getBean(PasswordHasher.class);
          String hash = hasher.hash("s3cret".toCharArray());
          assertTrue(hash.startsWith("$argon2id$"), hash);
          assertTrue(hasher.matches("s3cret".toCharArray(), hash));
          assertFalse(hasher.matches("other".toCharArray(), hash));
        });
  }

  @Test
  void bcryptIsAvailableForMigratedCredentials() {
    runner
        .withPropertyValues("axiz.crypto.password.algorithm=bcrypt")
        .run(
            ctx -> {
              PasswordHasher hasher = ctx.getBean(PasswordHasher.class);
              String hash = hasher.hash("s3cret".toCharArray());
              assertTrue(hash.startsWith("$2a$12$"), hash);
              assertTrue(hasher.matches("s3cret".toCharArray(), hash));
            });
  }

  @Test
  void anUnknownAlgorithmStopsTheStartup() {
    runner
        .withPropertyValues("axiz.crypto.password.algorithm=md5")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("Unsupported password algorithm: md5"));
  }

  @Test
  void withoutACurrentKeyThereIsNoFieldEncryption() {
    runner
        .withBean(SecretResolver.class, () -> store(Map.of()))
        .run(ctx -> assertThat(ctx).doesNotHaveBean(FieldEncryptor.class));
  }

  @Test
  void aMissingKeySecretStopsTheStartupNamingOnlyTheSecret() {
    runner
        .withBean(SecretResolver.class, () -> store(Map.of()))
        .withPropertyValues("axiz.crypto.fields.current-key-id=k9")
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .isInstanceOf(SecretNotFoundException.class)
                    .hasMessageContaining("field-key-k9"));
  }

  @Test
  void theCurrentKeyIsUsableEvenWhenNotListedAndOldKeysStillDecrypt() {
    String k1 = newKey();
    String k2 = newKey();
    String oldCipher =
        new FieldEncryptor(StaticKeyProvider.fromBase64("k1", Map.of("k1", k1)))
            .encrypt("4111111111111111", "card.pan");
    Map<String, String> secrets = new HashMap<>();
    secrets.put("vault/k1", k1);
    secrets.put("vault/k2", k2);
    runner
        .withBean(SecretResolver.class, () -> store(secrets))
        .withPropertyValues(
            "axiz.crypto.fields.current-key-id=k2",
            "axiz.crypto.fields.key-ids=k1",
            "axiz.crypto.fields.secret-prefix=vault/")
        .run(
            ctx -> {
              FieldEncryptor enc = ctx.getBean(FieldEncryptor.class);
              assertEquals("4111111111111111", enc.decrypt(oldCipher, "card.pan"));
              String fresh = enc.encrypt("4111111111111111", "card.pan");
              assertEquals("4111111111111111", enc.decrypt(fresh, "card.pan"));
              assertThat(ctx).doesNotHaveBean(BlindIndex.class);
            });
  }

  @Test
  void theRegistryIsClearedWhenTheContextCloses() {
    String hmac = Base64.getEncoder().encodeToString(new byte[32]);
    runner
        .withBean(
            SecretResolver.class, () -> store(Map.of("field-key-k1", newKey(), "blind", hmac)))
        .withPropertyValues(
            "axiz.crypto.fields.current-key-id=k1", "axiz.crypto.fields.blind-index-secret=blind")
        .run(
            ctx -> {
              String index = EncryptionRegistry.blindIndex("12345678", "customer.document");
              assertEquals(
                  ctx.getBean(BlindIndex.class).index("12345678", "customer.document"), index);
            });
    assertThrows(IllegalStateException.class, EncryptionRegistry::encryptor);
  }

  @Test
  void kmsEnvelopeEncryptionIsWiredOnlyWithAKeyId() {
    DataKeyProvider fakeKms =
        new DataKeyProvider() {
          private final byte[] key = AesGcm.newKey().getEncoded();

          @Override
          public DataKey generate(String context) {
            return new DataKey(key, "wrapped".getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public byte[] decrypt(byte[] encryptedKey, String context) {
            return key.clone();
          }
        };
    ApplicationContextRunner kms =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizCryptoKmsAutoConfiguration.class))
            .withPropertyValues("axiz.aws.region=us-east-1");
    kms.run(ctx -> assertThat(ctx).doesNotHaveBean(EnvelopeEncryptor.class));
    kms.withBean(DataKeyProvider.class, () -> fakeKms)
        .withPropertyValues("axiz.crypto.kms.key-id=alias/payments")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(KmsClient.class);
              EnvelopeEncryptor envelope = ctx.getBean(EnvelopeEncryptor.class);
              byte[] plain = "statement".getBytes(StandardCharsets.UTF_8);
              byte[] sealed = envelope.encrypt(plain, "statements");
              assertArrayEquals(plain, envelope.decrypt(sealed, "statements"));
            });
  }
}
