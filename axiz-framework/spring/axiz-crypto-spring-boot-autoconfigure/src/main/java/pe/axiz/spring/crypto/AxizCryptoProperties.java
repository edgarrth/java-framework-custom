package pe.axiz.spring.crypto;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Criptografía ({@code axiz.crypto.*}). La configuración solo nombra secretos; el material de
 * claves se resuelve en el arranque mediante el {@code SecretResolver} y nunca aparece en la
 * configuración.
 *
 * @param fields cifrado de campos (AES-256-GCM, rotación de claves por id de clave)
 * @param password hash de contraseñas
 */
@ConfigurationProperties("axiz.crypto")
public record AxizCryptoProperties(@DefaultValue Fields fields, @DefaultValue Password password) {

  /**
   * Cifrado de campos.
   *
   * @param currentKeyId id de clave usado para los valores nuevos
   * @param keyIds todos los ids de clave que aún pueden descifrar (la rotación conserva los
   *     anteriores)
   * @param secretPrefix el nombre del secreto de una clave es {@code secretPrefix + keyId} (base64,
   *     32 bytes)
   * @param indexCurrentKeyId id de la clave HMAC de escritura activa
   * @param indexSecrets ids de las claves HMAC de lectura activas asociados a nombres de secretos
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
   * Hash de contraseñas.
   *
   * @param algorithm {@code argon2id}, {@code bcrypt} o {@code pbkdf2}
   */
  public record Password(@DefaultValue("argon2id") String algorithm) {}
}
