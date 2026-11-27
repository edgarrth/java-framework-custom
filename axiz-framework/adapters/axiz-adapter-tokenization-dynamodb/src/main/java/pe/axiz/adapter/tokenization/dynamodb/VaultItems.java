package pe.axiz.adapter.tokenization.dynamodb;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.vault.FingerprintKeyState;
import pe.axiz.core.tokenization.vault.VaultIntegrityException;
import pe.axiz.core.tokenization.vault.VaultRecord;
import pe.axiz.core.tokenization.vault.VaultScope;
import pe.axiz.core.tokenization.vault.WrappingKeyState;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Codec estricto del esquema 1 de la bóveda (atributo {@code schema} igual a 1). No se almacena
 * ningún valor original ni atributo TTL automático de DynamoDB.
 */
final class VaultItems {
  private static final Set<String> RECORD_FIELDS =
      Set.of(
          "pk",
          "schema",
          "token",
          "scope",
          "type",
          "tenant",
          "domain",
          "profile",
          "canonicalVersion",
          "keyset",
          "fingerprints",
          "revision",
          "ciphertext",
          "createdAt",
          "expiresAt",
          "metadata");

  private VaultItems() {}

  static AttributeValue s(String value) {
    return AttributeValue.builder().s(value).build();
  }

  static AttributeValue n(long value) {
    return AttributeValue.builder().n(Long.toString(value)).build();
  }

  static AttributeValue strings(List<String> values) {
    return AttributeValue.builder().l(values.stream().map(VaultItems::s).toList()).build();
  }

  static Map<String, AttributeValue> key(String pk) {
    return Map.of("pk", s(pk));
  }

  static String tokenKey(Token token) {
    return "T#" + token.reveal();
  }

  static String controlKey(String keyset) {
    if (keyset == null || keyset.length() > 64 || !keyset.matches("[A-Za-z0-9][A-Za-z0-9_.:@/-]*"))
      throw new IllegalArgumentException("Invalid vault keyset identifier");
    return "C#" + keyset;
  }

  static String wrappingKey(String keyset) {
    controlKey(keyset);
    return "E#" + keyset;
  }

  static Map<String, AttributeValue> wrapping(WrappingKeyState state) {
    var result =
        new HashMap<>(
            control(
                new FingerprintKeyState(
                    state.keysetId(), state.version(), state.writeKeyId(), state.readKeyIds())));
    result.put("pk", s(wrappingKey(state.keysetId())));
    return Map.copyOf(result);
  }

  static WrappingKeyState wrapping(String keyset, Map<String, AttributeValue> item) {
    if (!text(item, "pk", 66).equals(wrappingKey(keyset))) throw new VaultIntegrityException();
    var normalized = new HashMap<>(item);
    normalized.put("pk", s(controlKey(keyset)));
    var state = control(keyset, normalized);
    return new WrappingKeyState(
        state.keysetId(), state.version(), state.writeKeyId(), state.readKeyIds());
  }

  static String fingerprintKey(VaultScope scope, KeyedHash hash) {
    checkHash(scope, hash);
    return "F#"
        + scope.encoded()
        + "#"
        + hash.keyId()
        + "#"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(hash.digest());
  }

  static void checkHash(VaultScope scope, KeyedHash hash) {
    if (!hash.profileId().equals(scope.profileId())
        || hash.canonicalVersion() != scope.canonicalVersion()) throw new VaultIntegrityException();
  }

  static void candidates(VaultScope scope, List<KeyedHash> candidates) {
    if (candidates.isEmpty() || candidates.size() > 16)
      throw new IllegalArgumentException("Invalid fingerprint candidate count");
    var keys = new HashSet<String>();
    for (KeyedHash hash : candidates) {
      checkHash(scope, hash);
      if (!keys.add(hash.keyId()))
        throw new IllegalArgumentException("Duplicate fingerprint candidate key");
    }
  }

  static Map<String, AttributeValue> record(VaultRecord record) {
    var item = new HashMap<String, AttributeValue>();
    item.put("pk", s(tokenKey(record.token())));
    item.put("schema", n(1));
    item.put("token", s(record.token().reveal()));
    item.put("scope", s(record.scope().encoded()));
    item.put("type", s(record.scope().type().value()));
    item.put("tenant", s(record.scope().tenant()));
    item.put("domain", s(record.scope().domain().value()));
    item.put("profile", s(record.scope().profileId()));
    item.put("canonicalVersion", n(record.scope().canonicalVersion()));
    item.put("keyset", s(record.keysetId()));
    item.put(
        "fingerprints", strings(record.fingerprints().stream().map(KeyedHash::encoded).toList()));
    item.put("revision", n(record.revision()));
    item.put(
        "ciphertext",
        AttributeValue.builder().b(SdkBytes.fromByteArray(record.ciphertext())).build());
    item.put("createdAt", s(record.createdAt().toString()));
    item.put("expiresAt", s(record.expiresAt().toString()));
    var metadata = new HashMap<String, AttributeValue>();
    record.metadata().forEach((key, value) -> metadata.put(key, s(value)));
    item.put("metadata", AttributeValue.builder().m(metadata).build());
    return Map.copyOf(item);
  }

  static VaultRecord record(Map<String, AttributeValue> item) {
    try {
      if (!item.keySet().equals(RECORD_FIELDS) || number(item, "schema") != 1)
        throw new VaultIntegrityException();
      Token token = Token.parse(text(item, "token", 26));
      var scope =
          new VaultScope(
              new TokenType(text(item, "type", 32)),
              text(item, "tenant", 128),
              new TokenDomain(text(item, "domain", 64)),
              text(item, "profile", 64),
              Math.toIntExact(number(item, "canonicalVersion")));
      if (!text(item, "pk", 28).equals(tokenKey(token))
          || !text(item, "scope", 512).equals(scope.encoded())) throw new VaultIntegrityException();
      AttributeValue ciphertext = item.get("ciphertext");
      if (ciphertext == null
          || ciphertext.type() != AttributeValue.Type.B
          || ciphertext.b().asByteBuffer().remaining() > 100_000)
        throw new VaultIntegrityException();
      AttributeValue metadata = item.get("metadata");
      if (metadata == null || metadata.type() != AttributeValue.Type.M || metadata.m().size() > 16)
        throw new VaultIntegrityException();
      var decoded = new HashMap<String, String>();
      for (String key : metadata.m().keySet()) decoded.put(key, text(metadata.m(), key, 256));
      return VaultRecord.builder()
          .token(token)
          .scope(scope)
          .keysetId(text(item, "keyset", 64))
          .fingerprints(
              list(item, "fingerprints", 16, 1500).stream().map(KeyedHash::parse).toList())
          .revision(number(item, "revision"))
          .ciphertext(ciphertext.b().asByteArray())
          .createdAt(Instant.parse(text(item, "createdAt", 40)))
          .expiresAt(Instant.parse(text(item, "expiresAt", 40)))
          .metadata(decoded)
          .build();
    } catch (RuntimeException invalid) {
      throw new VaultIntegrityException();
    }
  }

  static Map<String, AttributeValue> fingerprint(VaultRecord record, KeyedHash hash) {
    return Map.of(
        "pk",
        s(fingerprintKey(record.scope(), hash)),
        "schema",
        n(1),
        "scope",
        s(record.scope().encoded()),
        "token",
        s(record.token().reveal()),
        "keyset",
        s(record.keysetId()),
        "keyId",
        s(hash.keyId()));
  }

  static Token fingerprint(Map<String, AttributeValue> item, VaultScope scope, KeyedHash hash) {
    try {
      if (!item.keySet().equals(Set.of("pk", "schema", "scope", "token", "keyset", "keyId"))
          || number(item, "schema") != 1
          || !text(item, "keyId", 32).equals(hash.keyId())
          || !text(item, "pk", 600).equals(fingerprintKey(scope, hash))
          || !text(item, "scope", 512).equals(scope.encoded())) throw new VaultIntegrityException();
      return Token.parse(text(item, "token", 26));
    } catch (RuntimeException invalid) {
      throw new VaultIntegrityException();
    }
  }

  static Map<String, AttributeValue> control(FingerprintKeyState state) {
    return Map.of(
        "pk",
        s(controlKey(state.keysetId())),
        "schema",
        n(1),
        "keyset",
        s(state.keysetId()),
        "version",
        n(state.version()),
        "writeKey",
        s(state.writeKeyId()),
        "readKeys",
        strings(state.readKeyIds()));
  }

  static FingerprintKeyState control(String keyset, Map<String, AttributeValue> item) {
    try {
      if (!item.keySet().equals(Set.of("pk", "schema", "keyset", "version", "writeKey", "readKeys"))
          || number(item, "schema") != 1
          || !text(item, "pk", 66).equals(controlKey(keyset))
          || !text(item, "keyset", 64).equals(keyset)) throw new VaultIntegrityException();
      return new FingerprintKeyState(
          keyset,
          number(item, "version"),
          text(item, "writeKey", 32),
          list(item, "readKeys", 16, 32));
    } catch (RuntimeException invalid) {
      throw new VaultIntegrityException();
    }
  }

  static String text(Map<String, AttributeValue> item, String name, int maximum) {
    AttributeValue value = item.get(name);
    if (value == null || value.type() != AttributeValue.Type.S || value.s().length() > maximum)
      throw new VaultIntegrityException();
    return value.s();
  }

  private static long number(Map<String, AttributeValue> item, String name) {
    AttributeValue value = item.get(name);
    if (value == null || value.type() != AttributeValue.Type.N || value.n().length() > 19)
      throw new VaultIntegrityException();
    long result = Long.parseLong(value.n());
    if (!Long.toString(result).equals(value.n())) throw new VaultIntegrityException();
    return result;
  }

  private static List<String> list(
      Map<String, AttributeValue> item, String name, int count, int maximum) {
    AttributeValue value = item.get(name);
    if (value == null
        || value.type() != AttributeValue.Type.L
        || value.l().isEmpty()
        || value.l().size() > count) throw new VaultIntegrityException();
    return value.l().stream().map(entry -> text(Map.of("value", entry), "value", maximum)).toList();
  }
}
