package pe.axiz.adapter.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsAsyncClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

class KmsAsyncDataKeyProviderTest {
  @Test
  void localAndFatalFailuresKeepTheirIdentityAndCheckedFailuresKeepTheirCause() {
    for (Throwable failure :
        List.of(
            new IllegalStateException("closed"),
            new LinkageError("driver failed"),
            new IOException("transport"))) {
      var raw = new Pending();
      var generated =
          new KmsAsyncDataKeyProvider(raw, "alias/payments")
              .generate("account")
              .toCompletableFuture();
      raw.generate.completeExceptionally(failure);
      Throwable actual = assertThrows(CompletionException.class, generated::join).getCause();
      if (failure instanceof IOException) assertSame(failure, actual.getCause());
      else assertSame(failure, actual);
    }
  }

  @Test
  void nativeRequestsKeepKeyStrengthContextAndOwnedInputBytes() {
    var raw = new Pending();
    var provider = new KmsAsyncDataKeyProvider(raw, "alias/payments");
    var generated = provider.generate("account").toCompletableFuture();
    assertEquals("alias/payments", raw.generateRequest.keyId());
    assertEquals(DataKeySpec.AES_256, raw.generateRequest.keySpec());
    assertEquals(Map.of("axiz-context", "account"), raw.generateRequest.encryptionContext());
    byte[] plain = AesGcm.newKey().getEncoded();
    byte[] wrapped = {1, 2, 3};
    raw.generate.complete(
        GenerateDataKeyResponse.builder()
            .plaintext(SdkBytes.fromByteArray(plain))
            .ciphertextBlob(SdkBytes.fromByteArray(wrapped))
            .build());
    assertArrayEquals(plain, generated.join().plaintext());
    var decrypted = provider.decrypt(wrapped, "account").toCompletableFuture();
    wrapped[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, raw.decryptRequest.ciphertextBlob().asByteArray());
    assertEquals("alias/payments", raw.decryptRequest.keyId());
    assertEquals(Map.of("axiz-context", "account"), raw.decryptRequest.encryptionContext());
    raw.decrypt.complete(
        DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(plain)).build());
    assertArrayEquals(plain, decrypted.join());
  }

  @Test
  void cancellationReachesBothExactSdkRequests() {
    var raw = new Pending();
    var provider = new KmsAsyncDataKeyProvider(raw, "alias/payments");
    assertTrue(provider.generate("account").toCompletableFuture().cancel(true));
    assertTrue(raw.generate.isCancelled());
    assertTrue(provider.decrypt(new byte[] {1}, "account").toCompletableFuture().cancel(false));
    assertTrue(raw.decrypt.isCancelled());
  }

  @Test
  void remoteFailuresRetainTheirCauseWithoutReturningKeyMaterial() {
    var raw = new Pending();
    var provider = new KmsAsyncDataKeyProvider(raw, "alias/payments");
    var generated = provider.generate("account").toCompletableFuture();
    raw.generate.completeExceptionally(KmsException.builder().message("denied").build());
    var failedGenerate =
        assertInstanceOf(
            CryptoException.class,
            assertThrows(CompletionException.class, generated::join).getCause());
    assertInstanceOf(KmsException.class, failedGenerate.getCause());
    var decrypted = provider.decrypt(new byte[] {1}, "account").toCompletableFuture();
    raw.decrypt.completeExceptionally(KmsException.builder().message("invalid context").build());
    assertInstanceOf(
        CryptoException.class, assertThrows(CompletionException.class, decrypted::join).getCause());
  }

  private static final class Pending implements KmsAsyncClient {
    final CompletableFuture<GenerateDataKeyResponse> generate = new CompletableFuture<>();
    final CompletableFuture<DecryptResponse> decrypt = new CompletableFuture<>();
    GenerateDataKeyRequest generateRequest = GenerateDataKeyRequest.builder().build();
    DecryptRequest decryptRequest = DecryptRequest.builder().build();

    public CompletableFuture<GenerateDataKeyResponse> generateDataKey(
        GenerateDataKeyRequest request) {
      generateRequest = request;
      return generate;
    }

    public CompletableFuture<DecryptResponse> decrypt(DecryptRequest request) {
      decryptRequest = request;
      return decrypt;
    }

    public String serviceName() {
      return "kms";
    }

    public void close() {}
  }
}
