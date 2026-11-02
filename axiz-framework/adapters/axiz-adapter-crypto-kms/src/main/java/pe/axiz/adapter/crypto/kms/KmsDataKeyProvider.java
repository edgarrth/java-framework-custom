package pe.axiz.adapter.crypto.kms;

import java.util.Objects;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Data keys from AWS KMS. The context is sent as KMS encryption context, so a data key can only be
 * decrypted for the same context, and appears in CloudTrail for auditing.
 */
public final class KmsDataKeyProvider implements DataKeyProvider {

  static final String CONTEXT_KEY = "axiz-context";

  private final KmsClient kms;
  private final KmsRequests requests;

  public KmsDataKeyProvider(KmsClient kms, String keyId) {
    this.kms = Objects.requireNonNull(kms);
    this.requests = new KmsRequests(keyId);
  }

  @Override
  public DataKey generate(String context) {
    try {
      GenerateDataKeyResponse r = kms.generateDataKey(requests.generate(context));
      return new DataKey(r.plaintext().asByteArray(), r.ciphertextBlob().asByteArray());
    } catch (SdkException e) {
      throw new CryptoException("KMS GenerateDataKey failed", e);
    }
  }

  @Override
  public byte[] decrypt(byte[] encryptedKey, String context) {
    try {
      return kms.decrypt(requests.decrypt(encryptedKey, context)).plaintext().asByteArray();
    } catch (SdkException e) {
      throw new CryptoException("KMS Decrypt failed", e);
    }
  }
}
