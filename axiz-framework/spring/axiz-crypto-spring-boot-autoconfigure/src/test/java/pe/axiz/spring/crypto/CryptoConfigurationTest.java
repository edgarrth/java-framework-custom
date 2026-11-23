package pe.axiz.spring.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.IndexScope;
import pe.axiz.core.crypto.PasswordHasher;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.secrets.SecretNotFoundException;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.core.secrets.SecretValue;

/**
 * El material de claves proviene solo del almacén de secretos; el hash y el cifrado de sobre quedan
 * cableados.
 */
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
  void independentContextsCannotOverwriteOrCloseEachOthersKeys() {
    String hmac = Base64.getEncoder().encodeToString(new byte[32]);
    runner
        .withBean(
            SecretResolver.class, () -> store(Map.of("field-key-k1", newKey(), "blind", hmac)))
        .withPropertyValues(
            "axiz.crypto.fields.current-key-id=k1",
            "axiz.crypto.fields.index-current-key-id=i1",
            "axiz.crypto.fields.index-secrets.i1=blind")
        .run(
            first -> {
              FieldEncryptor enc = first.getBean(FieldEncryptor.class);
              String encrypted = enc.encrypt("00123456", "customer.document");
              runner
                  .withBean(SecretResolver.class, () -> store(Map.of("field-key-k1", newKey())))
                  .withPropertyValues("axiz.crypto.fields.current-key-id=k1")
                  .run(
                      second ->
                          assertThrows(
                              pe.axiz.core.crypto.CryptoException.class,
                              () ->
                                  second
                                      .getBean(FieldEncryptor.class)
                                      .decrypt(encrypted, "customer.document")));
              assertEquals("00123456", enc.decrypt(encrypted, "customer.document"));
              assertEquals(java.util.Set.of("i1"), first.getBean(BlindIndex.class).keyIds());
            });
  }

  @Test
  void resolvedSecretsAreClosedAndOwnedKeysAreClosedWithTheirContext() {
    var fieldSecret = SecretValue.of(newKey());
    var indexSecret = SecretValue.of(newKey());
    var provider = new AtomicReference<StaticKeyProvider>();
    var index = new AtomicReference<BlindIndex>();
    var registry = new SimpleMeterRegistry();
    try {
      runner
          .withPropertyValues(
              "axiz.crypto.fields.current-key-id=k1",
              "axiz.crypto.fields.index-current-key-id=i1",
              "axiz.crypto.fields.index-secrets.i1=index")
          .withBean(
              SecretResolver.class,
              () -> name -> Optional.of(name.equals("index") ? indexSecret : fieldSecret))
          .run(
              ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThrows(IllegalStateException.class, fieldSecret::revealChars);
                assertThrows(IllegalStateException.class, indexSecret::revealChars);
                provider.set(ctx.getBean(StaticKeyProvider.class));
                index.set(ctx.getBean(BlindIndex.class));
                ctx.getBean(MeterBinder.class).bindTo(registry);
                ctx.getBean(FieldEncryptor.class).encrypt("00123456", "customer.dni");
                assertEquals(
                    1,
                    registry
                        .get("axiz.crypto.field.encryptions")
                        .tag("keyId", "k1")
                        .functionCounter()
                        .count());
              });
      assertThrows(CryptoException.class, () -> provider.get().key("k1"));
      assertThrows(
          CryptoException.class,
          () ->
              index
                  .get()
                  .index("00123456", new IndexScope("field", "DNI", "t", "d", "identity", 1)));
    } finally {
      registry.close();
    }
  }

  @Test
  void malformedKeyMaterialStillClosesTheResolvedSecretAndDoesNotEchoIt() {
    for (String material : List.of("secret-canary", "á".repeat(44), "A".repeat(5000))) {
      var secret = SecretValue.of(material);
      runner
          .withPropertyValues("axiz.crypto.fields.current-key-id=k1")
          .withBean(SecretResolver.class, () -> name -> Optional.of(secret))
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure())
                    .hasStackTraceContaining("Invalid Base64 secret")
                    .hasStackTraceContaining("IllegalArgumentException");
                assertFalse(ctx.getStartupFailure().getMessage().contains(material));
                assertThrows(IllegalStateException.class, secret::revealChars);
              });
    }
  }

  @Test
  void encryptionAndIndexCannotReuseMaterialEvenUnderDifferentSecretNamesOrOldKeyIds() {
    String shared = newKey();
    runner
        .withPropertyValues(
            "axiz.crypto.fields.current-key-id=current",
            "axiz.crypto.fields.key-ids=old",
            "axiz.crypto.fields.index-current-key-id=index",
            "axiz.crypto.fields.index-secrets.index=separate-name")
        .withBean(
            SecretResolver.class,
            () ->
                store(
                    Map.of(
                        "field-key-current",
                        newKey(),
                        "field-key-old",
                        shared,
                        "separate-name",
                        shared)))
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "Field encryption and index keys must use different key material"));
  }
}
