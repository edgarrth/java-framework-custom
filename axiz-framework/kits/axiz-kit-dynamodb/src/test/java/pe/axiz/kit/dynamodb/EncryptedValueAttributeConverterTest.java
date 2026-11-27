package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import software.amazon.awssdk.enhanced.dynamodb.AttributeValueType;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class EncryptedValueAttributeConverterTest {
  @Test
  void storagePreservesTheAuthenticatedEnvelopeAndItsRecordBinding() {
    try (var keys = new StaticKeyProvider("v1", Map.of("v1", AesGcm.newKey()))) {
      var cipher = new FieldEncryptor(keys);
      var codec = new EncryptedValueAttributeConverter();
      var value =
          EncryptedValue.parse(cipher.encrypt("sensitive-document", "customer.document", "row-1"));
      var stored = codec.transformFrom(value);
      assertEquals(value.encoded(), stored.s());
      assertEquals(value, codec.transformTo(stored));
      assertEquals(
          "sensitive-document",
          cipher.decrypt(codec.transformTo(stored).encoded(), "customer.document", "row-1"));
      assertThrows(
          CryptoException.class,
          () -> cipher.decrypt(codec.transformTo(stored).encoded(), "customer.document", "row-2"));
      assertEquals(EnhancedType.of(EncryptedValue.class), codec.type());
      assertEquals(AttributeValueType.S, codec.attributeValueType());
    }
  }

  @Test
  void plaintextMalformedAndWrongAttributeTypesAreRejected() {
    var codec = new EncryptedValueAttributeConverter();
    for (var invalid :
        List.of(
            AttributeValue.fromS("cleartext"),
            AttributeValue.fromS("axz1:v1:bad"),
            AttributeValue.fromN("123"),
            AttributeValue.fromBool(true),
            AttributeValue.fromNul(true)))
      assertThrows(CryptoException.class, () -> codec.transformTo(invalid));
  }
}
