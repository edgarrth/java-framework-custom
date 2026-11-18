package pe.axiz.spring.crypto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cryptography ({@code axiz.crypto.*}). Configuration only names secrets; key material is resolved
 * at startup through the {@code SecretResolver} and never appears in configuration.
 *
 * @param fields field encryption (AES-256-GCM, key rotation by key id)
 * @param password password hashing
 */
@ConfigurationProperties("axiz.crypto")
public record AxizCryptoProperties(@DefaultValue Fields fields, @DefaultValue Password password) {

  /**
   * Field encryption.
   *
   * @param currentKeyId key id used for new values
   * @param keyIds every key id still able to decrypt (rotation keeps old ones)
   * @param secretPrefix secret name of a key is {@code secretPrefix + keyId} (base64, 32 bytes)
   * @param indexCurrentKeyId active HMAC write key id
   * @param indexSecrets active HMAC read key ids mapped to secret names
   */
  public record Fields(
      @Nullable String currentKeyId,
      @DefaultValue List<String> keyIds,
      @DefaultValue("field-key-") String secretPrefix,
      @Nullable String indexCurrentKeyId,
      @DefaultValue Map<String, String> indexSecrets) {
    public Fields {
      keyIds = List.copyOf(keyIds);
      if (currentKeyId != null && (currentKeyId.isBlank() || currentKeyId.contains("${")))
        throw new IllegalArgumentException(
            "axiz.crypto.fields.current-key-id must not be blank or unresolved");
      var allKeys = new HashSet<>(keyIds);
      if (allKeys.size() != keyIds.size())
        throw new IllegalArgumentException("Duplicate field key id");
      if (currentKeyId != null) allKeys.add(currentKeyId);
      if (allKeys.size() > 16
          || allKeys.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,32}")))
        throw new IllegalArgumentException("Invalid field key configuration");
      indexSecrets = Map.copyOf(indexSecrets);
      if (indexSecrets.size() > 16) throw new IllegalArgumentException("Too many index read keys");
      if (indexCurrentKeyId != null
          && (indexCurrentKeyId.isBlank() || indexCurrentKeyId.contains("${")))
        throw new IllegalArgumentException(
            "axiz.crypto.fields.index-current-key-id must not be blank or unresolved");
      if (indexCurrentKeyId != null && !indexSecrets.containsKey(indexCurrentKeyId))
        throw new IllegalArgumentException("The index write key must be in index-secrets");
      if (indexCurrentKeyId == null && !indexSecrets.isEmpty())
        throw new IllegalArgumentException("index-current-key-id is required with index-secrets");
      indexSecrets.forEach(
          (id, name) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || name.isBlank() || name.contains("${"))
              throw new IllegalArgumentException("Invalid index key configuration");
          });
    }
  }

  /**
   * Password hashing.
   *
   * @param algorithm {@code argon2id}, {@code bcrypt} or {@code pbkdf2}
   */
  public record Password(@DefaultValue("argon2id") String algorithm) {}
}
