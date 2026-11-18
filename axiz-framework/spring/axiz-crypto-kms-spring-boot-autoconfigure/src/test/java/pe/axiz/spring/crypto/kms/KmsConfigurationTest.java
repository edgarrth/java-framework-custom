package pe.axiz.spring.crypto.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
  void immutableArnValidationRejectsMalformedComponentsAndAcceptsMultiRegionKeys() {
    String multiRegion = OLD.substring(0, OLD.indexOf("key/")) + "key/mrk-" + "a".repeat(32);
    assertThat(
            new AxizCryptoKmsProperties(
                    multiRegion, Duration.ofMinutes(5), 1000, Map.of("v1", multiRegion))
                .writeRoute())
        .isEqualTo("v1");
    for (String invalid :
        List.of(
            OLD.replace("arn:", "bad:"),
            OLD.replace("aws:", "other:"),
            OLD.replace("aws:", "aws-:"),
            OLD.replace("aws:", "aws--cn:"),
            OLD.replace("aws:", "aws-CN:"),
            OLD.replace(":kms:", ":s3:"),
            OLD.replace("us-east-1", ""),
            OLD.replace("us-east-1", "US-EAST-1"),
            OLD.replace("123456789012", "12345678901"),
            OLD.replace("key/", "alias/"),
            OLD + ":trailing",
            "arn:aws:kms")) {
      assertThatThrownBy(
              () ->
                  new AxizCryptoKmsProperties(
                      invalid, Duration.ofMinutes(5), 1000, Map.of("v1", invalid)))
          .as("Malformed immutable ARN: %s", invalid)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("immutable key ARNs");
    }
  }

  @Test
  void arnValidationAcceptsPartitionsAndRejectsLongInvalidSuffixWithoutBacktracking() {
    for (String partition : new String[] {"aws", "aws-cn", "aws-us-gov", "aws-iso-e"}) {
      String arn = OLD.replace("arn:aws:", "arn:" + partition + ":");
      assertThat(
              new AxizCryptoKmsProperties(arn, Duration.ofMinutes(5), 1000, Map.of("v1", arn))
                  .writeRoute())
          .isEqualTo("v1");
    }
    String invalid = "arn:aws" + "-a".repeat(20_000) + ":invalid";
    assertTimeout(
        Duration.ofSeconds(2),
        () ->
            assertThatThrownBy(
                    () ->
                        new AxizCryptoKmsProperties(
                            invalid, Duration.ofMinutes(5), 1000, Map.of("v1", invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable key ARNs"));
  }

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
