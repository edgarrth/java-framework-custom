package pe.axiz.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import pe.axiz.core.crypto.DataKeyProvider.DataKey;

/**
 * Enrutamiento versionado dentro del campo de DEK envuelta; nunca contiene material de clave en
 * texto plano.
 */
final class DataKeyRouting {
  private static final int MAGIC = 0x41584431; // AXD1

  private DataKeyRouting() {}

  static <T> Map<String, T> providers(Map<String, T> providers, String writeKey) {
    if (providers.isEmpty() || providers.size() > 16 || !providers.containsKey(writeKey))
      throw new CryptoException("Invalid data-key ring");
    providers.keySet().forEach(DataKeyRouting::validateId);
    return Map.copyOf(providers);
  }

  static String validateId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}"))
      throw new CryptoException("Invalid data-key route");
    return id;
  }

  static String context(String keyId, String context) {
    if (context.isBlank()) throw new CryptoException("Data-key context is required");
    // El proveedor de envoltura subyacente autentica la ruta incluso si dos rutas comparten una
    // KEK. Solo está presente el contexto técnico configurado; el enlace del registro nunca se
    // envía a los logs de KMS.
    return "axiz-keyring-v1/"
        + keyId
        + "/"
        + Base64.getUrlEncoder().withoutPadding().encodeToString(StrictUtf8.encode(context, 1024));
  }

  static DataKey routed(DataKey source, String keyId) {
    source.validForEnvelope();
    byte[] id = validateId(keyId).getBytes(StandardCharsets.US_ASCII);
    byte[] encrypted = source.encrypted();
    if (encrypted.length + 7 + id.length > 0xFFFF)
      throw new CryptoException("Wrapped data key is too large");
    byte[] routed =
        ByteBuffer.allocate(7 + id.length + encrypted.length)
            .putInt(MAGIC)
            .put((byte) id.length)
            .put(id)
            .putShort((short) encrypted.length)
            .put(encrypted)
            .array();
    byte[] raw = source.plaintext();
    try {
      return new DataKey(raw, routed);
    } finally {
      Arrays.fill(raw, (byte) 0);
    }
  }

  static Route parse(byte[] bytes) {
    if (bytes.length < 9 || bytes.length > 0xFFFF)
      throw new CryptoException("Invalid routed data key");
    var input = ByteBuffer.wrap(bytes);
    if (input.getInt() != MAGIC) throw new CryptoException("Unsupported data-key routing format");
    int size = Byte.toUnsignedInt(input.get());
    if (size < 1 || size > 32 || input.remaining() < size + 3)
      throw new CryptoException("Invalid data-key route");
    byte[] id = new byte[size];
    input.get(id);
    for (byte value : id) if (value < 0) throw new CryptoException("Invalid data-key route");
    String keyId = validateId(new String(id, StandardCharsets.US_ASCII));
    int encryptedSize = Short.toUnsignedInt(input.getShort());
    if (encryptedSize < 1 || input.remaining() != encryptedSize)
      throw new CryptoException("Invalid routed key size");
    byte[] encrypted = new byte[encryptedSize];
    input.get(encrypted);
    return new Route(keyId, encrypted);
  }

  static final class Route {
    private final String keyId;
    private final byte[] encrypted;

    Route(String keyId, byte[] encrypted) {
      this.keyId = keyId;
      this.encrypted = encrypted.clone();
    }

    String keyId() {
      return keyId;
    }

    byte[] encrypted() {
      return encrypted.clone();
    }

    @Override
    public String toString() {
      return "DataKeyRoute[****]";
    }
  }
}
