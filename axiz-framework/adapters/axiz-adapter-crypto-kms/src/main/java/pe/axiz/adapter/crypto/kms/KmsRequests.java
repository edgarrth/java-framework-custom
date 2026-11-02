package pe.axiz.adapter.crypto.kms;

import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;

/** Same key id, AES strength and encryption context for both SDK client variants. */
final class KmsRequests {
  private final String keyId;

  KmsRequests(String keyId) {
    this.keyId = Objects.requireNonNull(keyId);
  }

  GenerateDataKeyRequest generate(String context) {
    return GenerateDataKeyRequest.builder()
        .keyId(keyId)
        .keySpec(DataKeySpec.AES_256)
        .encryptionContext(Map.of(KmsDataKeyProvider.CONTEXT_KEY, context))
        .build();
  }

  DecryptRequest decrypt(byte[] encryptedKey, String context) {
    return DecryptRequest.builder()
        .keyId(keyId)
        .ciphertextBlob(SdkBytes.fromByteArray(encryptedKey))
        .encryptionContext(Map.of(KmsDataKeyProvider.CONTEXT_KEY, context))
        .build();
  }
}
