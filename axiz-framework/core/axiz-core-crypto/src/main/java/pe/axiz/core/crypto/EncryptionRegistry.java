package pe.axiz.core.crypto;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Field encryptor and blind index used by persistence converters (JPA, DynamoDB) when the provider
 * creates them without dependency injection. Registered by the crypto starter at startup.
 */
public final class EncryptionRegistry {

  private static volatile @Nullable FieldEncryptor encryptor;
  private static volatile @Nullable BlindIndex blindIndex;

  private EncryptionRegistry() {}

  public static void register(FieldEncryptor fieldEncryptor, @Nullable BlindIndex index) {
    encryptor = Objects.requireNonNull(fieldEncryptor);
    blindIndex = index;
  }

  public static FieldEncryptor encryptor() {
    FieldEncryptor e = encryptor;
    if (e == null) {
      throw new IllegalStateException(
          "No FieldEncryptor registered: add axiz-crypto-spring-boot-starter");
    }
    return e;
  }

  /** Blind index of a value for equality search over an encrypted column. */
  public static String blindIndex(String value, String context) {
    BlindIndex b = blindIndex;
    if (b == null) {
      throw new IllegalStateException("No blind index key registered");
    }
    return b.index(value, context);
  }

  public static void reset() {
    encryptor = null;
    blindIndex = null;
  }
}
