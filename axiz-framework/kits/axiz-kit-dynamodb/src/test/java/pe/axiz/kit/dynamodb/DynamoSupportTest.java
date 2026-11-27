package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.money.Money;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DynamoSupportTest {
  private final CursorCodec cursors =
      new CursorCodec(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey())), false);

  @Test
  void unsignedCursorsMustBeRejected() {
    String unsigned =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"pk\":{\"S\":\"other-tenant\"}}".getBytes(StandardCharsets.UTF_8));
    assertThrows(InvalidCursorException.class, () -> cursors.decode(unsigned, "table/filter"));
  }

  @Test
  void cursorRoundTrip() {
    Map<String, AttributeValue> key =
        Map.of(
            "pk",
            AttributeValue.builder().s("customer#1").build(),
            "seq",
            AttributeValue.builder().n("42").build());
    String cursor = cursors.encode(key, "table/filter");
    assertEquals(key, cursors.decode(cursor, "table/filter"));
    assertNull(cursors.encode(Map.of(), "table/filter"));
    assertThrows(InvalidCursorException.class, () -> cursors.decode(" ", "table/filter"));
    assertThrows(
        InvalidCursorException.class, () -> cursors.decode("not-base64-json", "table/filter"));
  }

  @Test
  void converters() {
    MoneyAttributeConverter money = new MoneyAttributeConverter();
    assertEquals("PEN 150.00", money.transformFrom(Money.of("150", "PEN")).s());
    assertEquals(
        Money.of("150", "PEN"),
        money.transformTo(AttributeValue.builder().s("PEN 150.00").build()));
    FieldEncryptor encryptor =
        new FieldEncryptor(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey())));
    EncryptedAttributeConverter enc =
        new EncryptedAttributeConverter(encryptor) {
          protected String context() {
            return "card.pan";
          }
        };
    AttributeValue stored = enc.transformFrom("4111111111111111");
    assertEquals("4111111111111111", enc.transformTo(stored));
  }
}
