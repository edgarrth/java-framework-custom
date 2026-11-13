package pe.axiz.adapter.crypto.kms;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.commons.Stages;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.kms.KmsAsyncClient;

/** Native asynchronous KMS data keys, with preserved context and exact SDK cancellation. */
public final class KmsAsyncDataKeyProvider implements AsyncDataKeyProvider {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "KmsAsyncClient is a borrowed collaborator; sharing its identity preserves "
              + "lifecycle, transactions or instrumentation. The field is not an owned mutable "
              + "value.")
  private final KmsAsyncClient kms;

  private final KmsRequests requests;

  public KmsAsyncDataKeyProvider(KmsAsyncClient kms, String keyId) {
    this.kms = Objects.requireNonNull(kms);
    this.requests = new KmsRequests(keyId);
  }

  @Override
  public CompletionStage<DataKey> generate(String context) {
    return failure(
        Stages.map(
            Stages.call(() -> kms.generateDataKey(requests.generate(context))),
            response ->
                new DataKey(
                    response.plaintext().asByteArray(), response.ciphertextBlob().asByteArray())),
        "GenerateDataKey");
  }

  @Override
  public CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context) {
    return failure(
        Stages.map(
            Stages.call(() -> kms.decrypt(requests.decrypt(encryptedKey, context))),
            response -> response.plaintext().asByteArray()),
        "Decrypt");
  }

  private static <T> CompletionStage<T> failure(CompletionStage<T> work, String operation) {
    return Stages.recover(
        work,
        error -> {
          if (error instanceof SdkException)
            throw new CryptoException("KMS " + operation + " failed", error);
          if (error instanceof RuntimeException runtime) throw runtime;
          if (error instanceof Error fatal) throw fatal;
          throw new CryptoException("KMS " + operation + " failed", error);
        });
  }
}
