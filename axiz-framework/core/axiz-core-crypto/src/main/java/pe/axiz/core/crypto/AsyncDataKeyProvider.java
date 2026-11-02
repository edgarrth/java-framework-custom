package pe.axiz.core.crypto;

import java.util.concurrent.CompletionStage;

/** Native asynchronous envelope-key provider; cancellation must reach the remote operation. */
public interface AsyncDataKeyProvider {
  CompletionStage<DataKeyProvider.DataKey> generate(String context);

  CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context);
}
