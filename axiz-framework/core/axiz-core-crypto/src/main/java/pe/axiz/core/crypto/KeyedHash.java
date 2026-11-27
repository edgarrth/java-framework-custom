package pe.axiz.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.SelfMasking;

/**
 * HMAC versionado inmutable. Parsear la sintaxis de almacenamiento no autentica el valor ni su
 * alcance.
 */
public final class KeyedHash implements SelfMasking {
  private final String keyId;
  private final String profileId;
  private final int canonicalVersion;
  private final byte[] digest;

  KeyedHash(String keyId, IndexScope scope, byte[] digest) {
    this(keyId, scope.profileId(), scope.canonicalVersion(), digest);
  }

  private KeyedHash(String keyId, String profileId, int canonicalVersion, byte[] digest) {
    if (digest.length != 32) throw new CryptoException("Index must contain a full SHA-256 MAC");
    if (!keyId.matches("[A-Za-z0-9_-]{1,32}") || profileId.isBlank() || canonicalVersion < 1)
      throw new CryptoException("Invalid index envelope");
    StrictUtf8.encode(profileId, 1024);
    this.keyId = keyId;
    this.profileId = profileId;
    this.canonicalVersion = canonicalVersion;
    this.digest = digest.clone();
  }

  /**
   * Parser canónico acotado del almacenamiento; quienes llaman igualmente establecen el alcance
   * confiable y la política de claves.
   */
  public static KeyedHash parse(String value) {
    if (value == null || value.length() > 1500) throw new CryptoException("Invalid index envelope");
    String[] parts = value.split(":", 6);
    if (parts.length != 5 || !parts[0].equals("axi1"))
      throw new CryptoException("Invalid index envelope");
    try {
      var hash =
          new KeyedHash(
              parts[1],
              StrictUtf8.decode(Base64.getUrlDecoder().decode(parts[2]), 1024),
              Integer.parseInt(parts[3]),
              Base64.getUrlDecoder().decode(parts[4]));
      if (!hash.encoded().equals(value)) throw new CryptoException("Invalid index envelope");
      return hash;
    } catch (IllegalArgumentException malformed) {
      throw new CryptoException("Invalid index envelope");
    }
  }

  public String keyId() {
    return keyId;
  }

  public String profileId() {
    return profileId;
  }

  public int canonicalVersion() {
    return canonicalVersion;
  }

  public int formatVersion() {
    return 1;
  }

  public byte[] digest() {
    return digest.clone();
  }

  /** Representación estable en base de datos con codificación inequívoca del perfil. */
  public String encoded() {
    return "axi1:"
        + keyId
        + ":"
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(profileId.getBytes(StandardCharsets.UTF_8))
        + ":"
        + canonicalVersion
        + ":"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
  }

  @Override
  public String masked() {
    return "****";
  }

  @Override
  public String toString() {
    return "KeyedHash[****]";
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof KeyedHash hash
        && keyId.equals(hash.keyId)
        && profileId.equals(hash.profileId)
        && canonicalVersion == hash.canonicalVersion
        && MessageDigest.isEqual(digest, hash.digest);
  }

  @Override
  public int hashCode() {
    return 31 * keyId.hashCode() + Arrays.hashCode(digest);
  }
}
