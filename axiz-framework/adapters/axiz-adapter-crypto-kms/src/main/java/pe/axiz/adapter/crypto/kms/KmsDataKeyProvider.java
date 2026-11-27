package pe.axiz.adapter.crypto.kms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Claves de datos de AWS KMS. El contexto se envía como contexto de cifrado de KMS, de modo que una
 * clave de datos solo puede descifrarse para el mismo contexto, y aparece en CloudTrail para
 * auditoría.
 */
public final class KmsDataKeyProvider implements DataKeyProvider {

  static final String CONTEXT_KEY = "axiz-context";

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "KmsClient is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
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
      return KmsRequests.dataKey(r);
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
