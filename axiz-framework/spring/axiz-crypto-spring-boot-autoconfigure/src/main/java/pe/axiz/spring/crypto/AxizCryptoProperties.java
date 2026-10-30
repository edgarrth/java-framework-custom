package pe.axiz.spring.crypto;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cryptography ({@code axiz.crypto.*}). Configuration only names secrets; key material is resolved
 * at startup through the {@code SecretResolver} and never appears in configuration.
 *
 * @param fields field encryption (AES-256-GCM, key rotation by key id)
 * @param kms envelope encryption with AWS KMS
 * @param password password hashing
 */
@ConfigurationProperties("axiz.crypto")
public record AxizCryptoProperties(
    @DefaultValue Fields fields, @DefaultValue Kms kms, @DefaultValue Password password) {

  /**
   * Field encryption.
   *
   * @param currentKeyId key id used for new values
   * @param keyIds every key id still able to decrypt (rotation keeps old ones)
   * @param secretPrefix secret name of a key is {@code secretPrefix + keyId} (base64, 32 bytes)
   * @param blindIndexSecret secret name of the HMAC key for blind indexes
   * @param blindIndexBytes length of a blind index
   */
  public record Fields(
      @Nullable String currentKeyId,
      @DefaultValue List<String> keyIds,
      @DefaultValue("field-key-") String secretPrefix,
      @Nullable String blindIndexSecret,
      @DefaultValue("16") int blindIndexBytes) {
    public Fields {
      keyIds = List.copyOf(keyIds);
    }
  }

  /**
   * KMS envelope encryption.
   *
   * @param keyId KMS key id, ARN or alias
   * @param dataKeyMaxAge how long a data key is reused for encryption
   * @param dataKeyMaxUses how many encryptions a data key serves
   */
  public record Kms(
      @Nullable String keyId,
      @DefaultValue("5m") Duration dataKeyMaxAge,
      @DefaultValue("1000") long dataKeyMaxUses) {}

  /**
   * Password hashing.
   *
   * @param algorithm {@code argon2id}, {@code bcrypt} or {@code pbkdf2}
   */
  public record Password(@DefaultValue("argon2id") String algorithm) {}
}
