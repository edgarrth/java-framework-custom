package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.StaticKeyProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class CursorSecurityTest {
  private static final String BINDING = "tenant-a/payments/index/status=ACTIVE";
  private static final Map<String, AttributeValue> KEY =
      Map.of("pk", AttributeValue.fromS("tenant-a"), "sk", AttributeValue.fromN("42"));

  @Test
  void replicaRotationAndKeyRetirement() {
    var oldKey = AesGcm.newKey();
    var newKey = AesGcm.newKey();
    var old = new CursorCodec(new StaticKeyProvider("old", Map.of("old", oldKey)), false);
    String previous = old.encode(KEY, BINDING);
    var rotated =
        new CursorCodec(new StaticKeyProvider("new", Map.of("old", oldKey, "new", newKey)), false);
    assertEquals(KEY, rotated.decode(previous, BINDING));
    String current = rotated.encode(KEY, BINDING);
    var replica =
        new CursorCodec(new StaticKeyProvider("new", Map.of("old", oldKey, "new", newKey)), false);
    assertEquals(KEY, replica.decode(current, BINDING));
    var retired = new CursorCodec(new StaticKeyProvider("new", Map.of("new", newKey)), false);
    assertThrows(InvalidCursorException.class, () -> retired.decode(previous, BINDING));
    assertEquals(KEY, retired.decode(current, BINDING));
  }

  @Test
  void signatureCoversPayloadKeyIdAndQueryBinding() {
    var key = AesGcm.newKey();
    var codec = new CursorCodec(new StaticKeyProvider("a", Map.of("a", key, "b", key)), false);
    String cursor = codec.encode(KEY, BINDING);
    assertThrows(
        InvalidCursorException.class, () -> codec.decode(cursor, BINDING + "/another-query"));
    assertThrows(
        InvalidCursorException.class,
        () -> codec.decode(cursor.replace("v1.a.", "v1.b."), BINDING));
    String[] parts = cursor.split("\\.");
    parts[2] = parts[2].substring(0, parts[2].length() - 1) + (parts[2].endsWith("A") ? "B" : "A");
    assertThrows(
        InvalidCursorException.class, () -> codec.decode(String.join(".", parts), BINDING));
    assertThrows(
        InvalidCursorException.class,
        () -> codec.decode(cursor.substring(0, cursor.lastIndexOf('.')), BINDING));
    assertThrows(InvalidCursorException.class, () -> codec.decode("x".repeat(17000), BINDING));
  }

  @Test
  void encryptionIsOptionalAndAuthenticatedAndSupportsBinaryKeys() {
    var keys = new StaticKeyProvider("key", Map.of("key", AesGcm.newKey()));
    var encrypted = new CursorCodec(keys, true);
    var signed = new CursorCodec(keys, false);
    String cursor = encrypted.encode(KEY, BINDING);
    byte[] payload = Base64.getUrlDecoder().decode(cursor.split("\\.")[2]);
    assertEquals(1, payload[0]);
    assertFalse(new String(payload, StandardCharsets.UTF_8).contains("tenant-a"));
    assertEquals(KEY, signed.decode(cursor, BINDING));
    var binary = Map.of("pk", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[] {1, 2, 3})));
    assertEquals(binary, encrypted.decode(encrypted.encode(binary, BINDING), BINDING));
    assertThrows(IllegalArgumentException.class, () -> encrypted.encode(KEY, ""));
  }
}
