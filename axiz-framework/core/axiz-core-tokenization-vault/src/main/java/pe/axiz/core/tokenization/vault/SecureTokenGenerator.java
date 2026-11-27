package pe.axiz.core.tokenization.vault;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import pe.axiz.core.crypto.SecureRandoms;
import pe.axiz.core.tokenization.Token;

/**
 * Aleatoriedad DRBG con semilla al arrancar, aislada de los valores de entrada y del texto cifrado.
 */
public final class SecureTokenGenerator implements TokenGenerator {
  private final SecureRandom random = SecureRandoms.drbg("token-vault");

  /** Correlación UUIDv4 aleatoria independiente; no crea un registro de idempotencia. */
  public UUID operationId() {
    byte[] entropy = new byte[16];
    try {
      random.nextBytes(entropy);
      entropy[6] = (byte) ((entropy[6] & 0x0f) | 0x40);
      entropy[8] = (byte) ((entropy[8] & 0x3f) | 0x80);
      ByteBuffer bytes = ByteBuffer.wrap(entropy);
      return new UUID(bytes.getLong(), bytes.getLong());
    } finally {
      Arrays.fill(entropy, (byte) 0);
    }
  }

  @Override
  public Token next() {
    byte[] entropy = new byte[16];
    try {
      random.nextBytes(entropy);
      return Token.fromEntropy(entropy);
    } finally {
      Arrays.fill(entropy, (byte) 0);
    }
  }
}
