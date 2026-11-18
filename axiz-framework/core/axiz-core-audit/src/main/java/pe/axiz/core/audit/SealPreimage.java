package pe.axiz.core.audit;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.json.JsonWriter;

/** One versioned, length-prefixed definition shared by calculation and verification. */
final class SealPreimage {
  static final String GENESIS = "0".repeat(64);
  private static final int MAX_CONTENT_BYTES = 1024 * 1024;

  private SealPreimage() {}

  static void prepare(AuditSealer.Mode mode) {
    try {
      if (mode == AuditSealer.Mode.HMAC) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        mac.doFinal();
      } else if (mode == AuditSealer.Mode.HASH) {
        MessageDigest.getInstance("SHA-256").digest();
      }
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Audit seal algorithm unavailable", unavailable);
    }
  }

  static String hash(AuditEvent event, AuditIntegrity position, byte @Nullable [] key) {
    String keyId = position.keyId();
    boolean hmac = keyId != null;
    if (hmac && (key == null || key.length < 32 || key.length > 1024))
      throw new IllegalArgumentException("Audit HMAC requires a 32 to 1024 byte key");
    List<byte[]> parts = new ArrayList<>();
    parts.add(new byte[] {1});
    parts.add(new byte[] {(byte) (hmac ? 2 : 1)});
    parts.add(utf8(keyId == null ? "" : keyId));
    parts.add(utf8(position.chainId()));
    parts.add(number(position.sequence()));
    parts.add(utf8(position.previousHash()));
    AttemptRef previous = position.previousAttempt();
    parts.add(new byte[] {(byte) (previous == null ? 0 : 1)});
    if (previous != null) {
      parts.add(utf8(previous.chainId()));
      parts.add(number(previous.sequence()));
      parts.add(utf8(previous.hash()));
      parts.add(new byte[] {(byte) (previous.outcome() == AttemptOutcome.ROLLED_BACK ? 1 : 2)});
    }
    var content = new LinkedHashMap<>(event.toMap());
    content.remove("integrity");
    content.remove("persistence");
    parts.add(utf8(JsonWriter.writeCanonical(content)));
    int size = 0;
    for (byte[] part : parts) size = Math.addExact(size, Math.addExact(4, part.length));
    var preimage = ByteBuffer.allocate(size);
    for (byte[] part : parts) preimage.putInt(part.length).put(part);
    try {
      byte[] digest;
      if (hmac && key != null) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        digest = mac.doFinal(preimage.array());
      } else {
        digest = MessageDigest.getInstance("SHA-256").digest(preimage.array());
      }
      return HexFormat.of().formatHex(digest);
    } catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Audit seal algorithm unavailable", unavailable);
    }
  }

  private static byte[] number(long value) {
    return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
  }

  private static byte[] utf8(String value) {
    if (value.length() > MAX_CONTENT_BYTES)
      throw new IllegalArgumentException("Audit seal field too large");
    try {
      ByteBuffer bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      if (bytes.remaining() > MAX_CONTENT_BYTES)
        throw new IllegalArgumentException("Audit seal field too large");
      byte[] copy = new byte[bytes.remaining()];
      bytes.get(copy);
      return copy;
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException("Invalid audit seal text");
    }
  }
}
