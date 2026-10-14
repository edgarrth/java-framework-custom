package pe.axiz.core.crypto.kms;

import java.util.Map;
import java.util.Objects;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Data keys from AWS KMS. The context is sent as KMS encryption context, so a data key can only be
 * decrypted for the same context, and appears in CloudTrail for auditing.
 */
public final class KmsDataKeyProvider implements DataKeyProvider {

  static final String CONTEXT_KEY = "axiz-context";

  private final KmsClient kms;
  private final String keyId;

  public KmsDataKeyProvider(KmsClient kms, String keyId) {
    this.kms = Objects.requireNonNull(kms);
    this.keyId = Objects.requireNonNull(keyId);
  }

  @Override
  public DataKey generate(String context) {
    try {
      GenerateDataKeyResponse r =
          kms.generateDataKey(
              GenerateDataKeyRequest.builder()
                  .keyId(keyId)
                  .keySpec(DataKeySpec.AES_256)
                  .encryptionContext(Map.of(CONTEXT_KEY, context))
                  .build());
      return new DataKey(r.plaintext().asByteArray(), r.ciphertextBlob().asByteArray());
    } catch (SdkException e) {
      throw new CryptoException("KMS GenerateDataKey failed", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] encryptedKey, String context) {
    try {
      return kms.decrypt(
              DecryptRequest.builder()
                  .keyId(keyId)
                  .ciphertextBlob(SdkBytes.fromByteArray(encryptedKey))
                  .encryptionContext(Map.of(CONTEXT_KEY, context))
                  .build())
          .plaintext()
          .asByteArray();
    } catch (SdkException e) {
      throw new CryptoException("KMS Decrypt failed", e);
    }
  }
}
