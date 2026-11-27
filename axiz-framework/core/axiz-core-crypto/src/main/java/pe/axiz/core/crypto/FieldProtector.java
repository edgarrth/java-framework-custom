package pe.axiz.core.crypto;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import pe.axiz.core.secrets.SecretValue;

/**
 * Protección de campos sin estado, sin almacenamiento ni tokenización. Las claves de cifrado y de
 * HMAC son distintas.
 */
public final class FieldProtector {
  private final FieldEncryptor cipher;
  private final BlindIndex index;
  private final FieldProtectionPolicy policy;

  public FieldProtector(FieldEncryptor cipher, BlindIndex index, FieldProtectionPolicy policy) {
    this.cipher = Objects.requireNonNull(cipher);
    this.index = Objects.requireNonNull(index);
    this.policy = Objects.requireNonNull(policy);
  }

  public EncryptedValue encrypt(SecretValue original, FieldScope scope, String stableRowId) {
    String binding = FieldScope.require(stableRowId);
    return original.use(
        chars ->
            EncryptedValue.parse(
                Objects.requireNonNull(
                    cipher.encrypt(new String(chars), context(scope), binding))));
  }

  public KeyedHash index(SecretValue original, FieldScope scope) {
    return original.use(chars -> index.index(canonical(chars), indexScope(scope)));
  }

  public List<KeyedHash> candidates(SecretValue original, FieldScope scope) {
    return original.use(chars -> index.candidates(canonical(chars), indexScope(scope)));
  }

  public SecretValue decrypt(EncryptedValue value, FieldScope expected, String stableRowId) {
    return SecretValue.of(
        Objects.requireNonNull(
            cipher.decrypt(value.encoded(), context(expected), FieldScope.require(stableRowId))));
  }

  public ProtectedField protect(SecretValue original, FieldScope scope, String stableRowId) {
    // Se toma un snapshot una sola vez para que un cierre por parte de quien llama no pueda separar
    // las dos operaciones.
    return original.use(
        chars -> {
          try (SecretValue snapshot = SecretValue.of(chars)) {
            KeyedHash hash = index(snapshot, scope);
            return new ProtectedField(encrypt(snapshot, scope, stableRowId), hash);
          }
        });
  }

  private String canonical(char[] chars) {
    String original = new String(chars);
    byte[] validated = StrictUtf8.encode(original, 8192);
    Arrays.fill(validated, (byte) 0);
    return Objects.requireNonNull(policy.canonicalizer().apply(original));
  }

  private IndexScope indexScope(FieldScope scope) {
    return new IndexScope(
        policy.purpose(),
        policy.fieldType(),
        scope.tenant(),
        scope.domain(),
        policy.profileId(),
        policy.canonicalVersion());
  }

  private String context(FieldScope scope) {
    // Un digest de longitud fija de una delimitación inequívoca mantiene acotado el contexto de la
    // primitiva.
    byte[] framed =
        Aad.of(
            Aad.FIELD,
            policy.purpose(),
            policy.fieldType(),
            scope.tenant(),
            scope.domain(),
            policy.profileId(),
            Integer.toString(policy.canonicalVersion()));
    // Un digest base64url puede empezar con un dígito o '_' y no es en sí un identificador de
    // campo.
    return "field.v1_"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(Hashing.sha256(framed));
  }
}
