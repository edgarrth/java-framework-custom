package pe.axiz.spring.crypto.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.services.kms.KmsClient;

class KmsConfigurationTest {
  @Test
  void configuredBlankKmsKeyFailsInItsOwningIntegration() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCryptoKmsAutoConfiguration.class))
        .withPropertyValues("axiz.crypto.kms.key-id=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("must not be blank"));
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
            .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
            .withConfiguration(AutoConfigurations.of(AxizCryptoKmsAutoConfiguration.class))
            .withPropertyValues("axiz.aws.region=us-east-1");
    kms.run(ctx -> assertThat(ctx).doesNotHaveBean(EnvelopeEncryptor.class));
    kms.withBean(DataKeyProvider.class, () -> fakeKms)
        .withPropertyValues("axiz.crypto.kms.key-id=alias/payments")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(KmsClient.class);
              EnvelopeEncryptor envelope = ctx.getBean(EnvelopeEncryptor.class);
              byte[] plain = "statement".getBytes(StandardCharsets.UTF_8);
              byte[] sealed = envelope.encrypt(plain, "statements");
              assertArrayEquals(plain, envelope.decrypt(sealed, "statements"));
            });
  }

  static final String OLD =
      "arn:aws:kms:us-east-1:123456789012:key/11111111-1111-1111-1111-111111111111";
  static final String NEW =
      "arn:aws:kms:us-east-1:123456789012:key/22222222-2222-2222-2222-222222222222";

  @Test
  void immutableArnRingIsWiredAndInvalidMappingFailsBeforeOpeningClients() {
    var context =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AxizCryptoKmsAutoConfiguration.class))
            .withBean(KmsClient.class, () -> mock(KmsClient.class))
            .withPropertyValues(
                "axiz.crypto.kms.key-id=" + NEW,
                "axiz.crypto.kms.key-ring.old=" + OLD,
                "axiz.crypto.kms.key-ring.new=" + NEW);
    context.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          var keys = (KeyringDataKeyProvider) ctx.getBean(DataKeyProvider.class);
          assertThat(keys.writeKeyId()).isEqualTo("new");
          assertThat(keys.readKeyIds()).containsExactlyInAnyOrder("old", "new");
        });
    context
        .withPropertyValues("axiz.crypto.kms.key-ring.old=alias/mutable")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("immutable key ARNs"));
    context
        .withPropertyValues("axiz.crypto.kms.key-ring.old=" + NEW)
        .run(
            ctx -> assertThat(ctx.getStartupFailure()).hasStackTraceContaining("unique write key"));
    context
        .withPropertyValues("axiz.crypto.kms.key-id=alias/missing")
        .run(
            ctx -> assertThat(ctx.getStartupFailure()).hasStackTraceContaining("unique write key"));
  }
}
