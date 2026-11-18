package pe.axiz.adapter.crypto.kms;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/** Same key id, AES strength and encryption context for both SDK client variants. */
final class KmsRequests {
  private final String keyId;

  KmsRequests(String keyId) {
    this.keyId = Objects.requireNonNull(keyId);
  }

  static DataKey dataKey(GenerateDataKeyResponse response) {
    if (response == null || response.plaintext() == null || response.ciphertextBlob() == null)
      throw new CryptoException("KMS returned an incomplete data key");
    byte[] raw = response.plaintext().asByteArray();
    try {
      return new DataKey(raw, response.ciphertextBlob().asByteArray());
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
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
