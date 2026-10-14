package pe.axiz.core.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

class KmsDataKeyProviderTest {

  /** In-memory KMS: encrypts data keys with a master key bound to the encryption context. */
  static final class FakeKms implements KmsClient {
    final SecretKey master = AesGcm.newKey();
    boolean fail;

    @Override
    public GenerateDataKeyResponse generateDataKey(GenerateDataKeyRequest request) {
      if (fail) {
        throw KmsException.builder().message("throttled").build();
      }
      byte[] dk = AesGcm.newKey().getEncoded();
      byte[] aad =
          request
              .encryptionContext()
              .get(KmsDataKeyProvider.CONTEXT_KEY)
              .getBytes(StandardCharsets.UTF_8);
      return GenerateDataKeyResponse.builder()
          .keyId(request.keyId())
          .plaintext(SdkBytes.fromByteArray(dk))
          .ciphertextBlob(SdkBytes.fromByteArray(AesGcm.encrypt(master, dk, aad)))
          .build();
    }

    @Override
    public DecryptResponse decrypt(DecryptRequest request) {
      byte[] aad =
          request
              .encryptionContext()
              .get(KmsDataKeyProvider.CONTEXT_KEY)
              .getBytes(StandardCharsets.UTF_8);
      try {
        return DecryptResponse.builder()
            .plaintext(
                SdkBytes.fromByteArray(
                    AesGcm.decrypt(master, request.ciphertextBlob().asByteArray(), aad)))
            .build();
      } catch (CryptoException e) {
        throw KmsException.builder().message("InvalidCiphertextException").build();
      }
    }

    @Override
    public String serviceName() {
      return "kms";
    }

    @Override
    public void close() {}
  }

  @Test
  void envelopeEncryptionOverKms() {
    FakeKms kms = new FakeKms();
    EnvelopeEncryptor envelope =
        new EnvelopeEncryptor(
            new KmsDataKeyProvider(kms, "alias/axiz"),
            Duration.ofMinutes(5),
            100,
            Clock.systemUTC());
    byte[] data = "secret".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = envelope.encrypt(data, "orders");
    assertArrayEquals(data, envelope.decrypt(encrypted, "orders"));
    assertThrows(
        CryptoException.class,
        () ->
            new EnvelopeEncryptor(
                    new KmsDataKeyProvider(kms, "alias/axiz"),
                    Duration.ofMinutes(5),
                    100,
                    Clock.systemUTC())
                .decrypt(encrypted, "payments"));
  }

  @Test
  void kmsFailuresAreCryptoExceptions() {
    FakeKms kms = new FakeKms();
    kms.fail = true;
    CryptoException e =
        assertThrows(CryptoException.class, () -> new KmsDataKeyProvider(kms, "k").generate("c"));
    assertEquals("KMS GenerateDataKey failed", e.getMessage());
  }
}
