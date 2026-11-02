package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.money.Money;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DynamoSupportTest {

  @AfterEach
  void reset() {
    EncryptionRegistry.reset();
  }

  @Test
  void cursorRoundTrip() {
    Map<String, AttributeValue> key =
        Map.of(
            "pk",
            AttributeValue.builder().s("customer#1").build(),
            "seq",
            AttributeValue.builder().n("42").build());
    String cursor = CursorCodec.encode(key);
    assertEquals(key, CursorCodec.decode(cursor));
    assertNull(CursorCodec.encode(Map.of()));
    assertNull(CursorCodec.decode(" "));
    assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode("not-base64-json"));
  }

  @Test
  void converters() {
    MoneyAttributeConverter money = new MoneyAttributeConverter();
    assertEquals("PEN 150.00", money.transformFrom(Money.of("150", "PEN")).s());
    assertEquals(
        Money.of("150", "PEN"),
        money.transformTo(AttributeValue.builder().s("PEN 150.00").build()));
    EncryptionRegistry.register(
        new FieldEncryptor(new StaticKeyProvider("k", Map.of("k", AesGcm.newKey()))), null);
    EncryptedAttributeConverter enc = new EncryptedAttributeConverter();
    AttributeValue stored = enc.transformFrom("4111111111111111");
    assertEquals("4111111111111111", enc.transformTo(stored));
  }
}
