package pe.axiz.spring.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

class ReactiveEnvelopeEncryptionTest {
  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCryptoKmsAutoConfiguration.class))
        .withPropertyValues("axiz.crypto.kms.key-id=alias/payments", "axiz.aws.region=us-east-1");
  }

  @Test
  void nativeClientIsReusedAndCancellationReachesItsPendingKeyLoad() {
    var raw = new Pending();
    reactive()
        .withBean(KmsAsyncClient.class, () -> raw)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .hasSingleBean(AsyncDataKeyProvider.class)
                  .hasSingleBean(AsyncEnvelopeEncryptor.class)
                  .hasSingleBean(ReactiveEnvelopeEncryption.class)
                  .doesNotHaveBean(KmsClient.class)
                  .doesNotHaveBean(DataKeyProvider.class)
                  .doesNotHaveBean(EnvelopeEncryptor.class)
                  .doesNotHaveBean(AsyncAwsClients.class);
              var encryption =
                  ctx.getBean(ReactiveEnvelopeEncryption.class).encrypt(new byte[] {1}, "account");
              assertEquals(0, raw.calls.get());
              StepVerifier.create(encryption).thenCancel().verify();
              assertEquals(1, raw.calls.get());
              assertTrue(raw.pending.isCancelled());
              StepVerifier.create(
                      ctx.getBean(ReactiveEnvelopeEncryption.class)
                          .decrypt(new byte[] {1}, "account"))
                  .expectError(CryptoException.class)
                  .verify();
            });
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void ownedNativeClientIsConfiguredAndWarmedAtStartup() {
    TestPropertyValues.of("aws.accessKeyId=test", "aws.secretAccessKey=test")
        .applyToSystemProperties(
            () ->
                reactive()
                    .withPropertyValues("axiz.aws.kms.endpoint=http://localhost:4566")
                    .run(
                        ctx -> {
                          assertThat(ctx)
                              .hasNotFailed()
                              .hasSingleBean(AsyncAwsClients.class)
                              .hasSingleBean(KmsAsyncClient.class)
                              .hasSingleBean(ReactiveEnvelopeEncryption.class)
                              .doesNotHaveBean(KmsClient.class);
                          assertThat(
                                  ctx.getBean(KmsAsyncClient.class)
                                      .serviceClientConfiguration()
                                      .endpointOverride())
                              .hasValue(java.net.URI.create("http://localhost:4566"));
                        }));
  }

  @Test
  void customKeyProviderAvoidsSdkResourcesAndBothFacadeDirectionsRoundTrip() {
    reactive()
        .withBean(AsyncDataKeyProvider.class, ReactiveEnvelopeEncryptionTest::keys)
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasNotFailed()
                  .doesNotHaveBean(AsyncAwsClients.class)
                  .doesNotHaveBean(KmsAsyncClient.class)
                  .doesNotHaveBean(KmsClient.class);
              var encryption = ctx.getBean(ReactiveEnvelopeEncryption.class);
              byte[] original = {1, 2, 3};
              StepVerifier.create(
                      encryption
                          .encrypt(original, "account")
                          .flatMap(ciphertext -> encryption.decrypt(ciphertext, "account")))
                  .expectNextMatches(plain -> Arrays.equals(original, plain))
                  .verifyComplete();
            });
  }

  @Test
  void customEncryptorBacksOffAllDefaultProviderResources() {
    var own = new AsyncEnvelopeEncryptor(keys(), Duration.ofMinutes(1), 2, Clock.systemUTC());
    reactive()
        .withBean(AsyncEnvelopeEncryptor.class, () -> own)
        .run(
            ctx ->
                assertThat(ctx)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveEnvelopeEncryption.class)
                    .doesNotHaveBean(AsyncDataKeyProvider.class)
                    .doesNotHaveBean(AsyncAwsClients.class)
                    .doesNotHaveBean(KmsAsyncClient.class));
  }

  private static AsyncDataKeyProvider keys() {
    byte[] plain = AesGcm.newKey().getEncoded();
    return new AsyncDataKeyProvider() {
      public CompletionStage<DataKeyProvider.DataKey> generate(String context) {
        return CompletableFuture.completedFuture(
            new DataKeyProvider.DataKey(plain, new byte[] {1, 2, 3}));
      }

      public CompletionStage<byte[]> decrypt(byte[] encrypted, String context) {
        return CompletableFuture.completedFuture(plain.clone());
      }
    };
  }

  private static final class Pending implements KmsAsyncClient {
    final AtomicInteger calls = new AtomicInteger();
    final CompletableFuture<GenerateDataKeyResponse> pending = new CompletableFuture<>();

    public CompletableFuture<GenerateDataKeyResponse> generateDataKey(
        GenerateDataKeyRequest request) {
      calls.incrementAndGet();
      return pending;
    }

    public String serviceName() {
      return "kms";
    }

    public void close() {}
  }
}
