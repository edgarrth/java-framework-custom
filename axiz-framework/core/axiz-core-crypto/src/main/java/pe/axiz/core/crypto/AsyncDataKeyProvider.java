package pe.axiz.core.crypto;

import java.util.concurrent.CompletionStage;

/**
 * Native envelope-key provider. Results transfer ownership (close DataKey, clear raw byte arrays).
 * Cancellation must reach the remote operation; shared plaintext arrays are forbidden.
 */
public interface AsyncDataKeyProvider {
  CompletionStage<DataKeyProvider.DataKey> generate(String context);

  CompletionStage<byte[]> decrypt(byte[] encryptedKey, String context);
}
