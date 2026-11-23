package pe.axiz.kit.dynamodb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.KeyProvider;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.json.JsonWriter;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Paginación autenticada y ligada a la consulta, con cifrado opcional y claves rotativas
 * compartidas. Quien llama igualmente construye la consulta a partir de su tenant y permisos
 * autenticados.
 */
public final class CursorCodec {
  private static final int MAX_CURSOR_LENGTH = 16384;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private final KeyProvider keys;
  private final boolean encrypt;

  public CursorCodec(KeyProvider keys, boolean encrypt) {
    this.keys = Objects.requireNonNull(keys);
    this.encrypt = encrypt;
    key(keys.currentKeyId());
  }

  public @Nullable String encode(
      @Nullable Map<String, AttributeValue> lastEvaluatedKey, String binding) {
    requireBinding(binding);
    if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) return null;
    Map<String, Object> json = new LinkedHashMap<>();
    lastEvaluatedKey.forEach(
        (name, value) -> {
          if (value.n() != null) json.put(name, Map.of("N", value.n()));
          else if (value.s() != null) json.put(name, Map.of("S", value.s()));
          else if (value.b() != null)
            json.put(name, Map.of("B", ENCODER.encodeToString(value.b().asByteArray())));
          else
            throw new IllegalArgumentException(
                "Pagination keys must be strings, numbers or binary");
        });
    String kid = keys.currentKeyId();
    SecretKey key = key(kid);
    byte[] plain = JsonWriter.write(json).getBytes(StandardCharsets.UTF_8);
    byte[] data =
        encrypt ? AesGcm.encrypt(derived(key, "encrypt"), plain, frame("v1", kid, binding)) : plain;
    String payload =
        ENCODER.encodeToString(
            ByteBuffer.allocate(data.length + 1).put((byte) (encrypt ? 1 : 0)).put(data).array());
    String signed = "v1." + kid + "." + payload;
    String cursor =
        signed + "." + ENCODER.encodeToString(hmac(derived(key, "sign"), frame(signed, binding)));
    if (cursor.length() > MAX_CURSOR_LENGTH)
      throw new IllegalArgumentException("Pagination key exceeds cursor limit");
    return cursor;
  }

  public @Nullable Map<String, AttributeValue> decode(@Nullable String cursor, String binding) {
    requireBinding(binding);
    if (cursor == null) return null;
    try {
      if (cursor.length() > MAX_CURSOR_LENGTH) throw new InvalidCursorException();
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 4 || !parts[0].equals("v1")) throw new InvalidCursorException();
      SecretKey key = key(parts[1]);
      byte[] signature = canonical(parts[3]);
      byte[] expected =
          hmac(derived(key, "sign"), frame(parts[0] + "." + parts[1] + "." + parts[2], binding));
      if (signature.length != 32 || !MessageDigest.isEqual(expected, signature))
        throw new InvalidCursorException();
      byte[] payload = canonical(parts[2]);
      if (payload.length < 2) throw new InvalidCursorException();
      byte mode = payload[0];
      byte[] data = Arrays.copyOfRange(payload, 1, payload.length);
      if (mode == 1)
        data = AesGcm.decrypt(derived(key, "encrypt"), data, frame("v1", parts[1], binding));
      else if (mode != 0) throw new InvalidCursorException();
      Map<String, @Nullable Object> json =
          JsonReader.parseObject(new String(data, StandardCharsets.UTF_8));
      if (json.isEmpty() || json.size() > 32) throw new InvalidCursorException();
      Map<String, AttributeValue> result = new LinkedHashMap<>();
      json.forEach(
          (name, value) -> {
            if (!(value instanceof Map<?, ?> map) || map.size() != 1)
              throw new InvalidCursorException();
            if (map.get("S") instanceof String text) result.put(name, AttributeValue.fromS(text));
            else if (map.get("N") instanceof String number)
              result.put(name, AttributeValue.fromN(number));
            else if (map.get("B") instanceof String bytes)
              result.put(name, AttributeValue.fromB(SdkBytes.fromByteArray(canonical(bytes))));
            else throw new InvalidCursorException();
          });
      return Map.copyOf(result);
    } catch (RuntimeException malformed) {
      throw new InvalidCursorException();
    }
  }

  private SecretKey key(String kid) {
    if (!kid.matches("[A-Za-z0-9_-]{1,32}"))
      throw new IllegalArgumentException("Invalid cursor key id");
    SecretKey key = keys.key(kid);
    if (key.getEncoded().length < 32)
      throw new IllegalArgumentException("Cursor keys must have at least 256 bits");
    return key;
  }

  private static byte[] canonical(String value) {
    byte[] decoded = DECODER.decode(value);
    if (!ENCODER.encodeToString(decoded).equals(value)) throw new InvalidCursorException();
    return decoded;
  }

  private static void requireBinding(String binding) {
    if (binding.isBlank() || binding.length() > 4096)
      throw new IllegalArgumentException("A bounded table/index/filter binding is required");
  }

  private static SecretKey derived(SecretKey key, String purpose) {
    return new SecretKeySpec(
        hmac(key, ("axiz.cursor.v1." + purpose).getBytes(StandardCharsets.UTF_8)), "AES");
  }

  private static byte[] hmac(SecretKey key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", failure);
    }
  }

  private static byte[] frame(String... values) {
    byte[][] bytes =
        Arrays.stream(values)
            .map(value -> value.getBytes(StandardCharsets.UTF_8))
            .toArray(byte[][]::new);
    int size = Arrays.stream(bytes).mapToInt(value -> Integer.BYTES + value.length).sum();
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] value : bytes) buffer.putInt(value.length).put(value);
    return buffer.array();
  }
}
