package pe.axiz.kit.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class EncryptedTableSchemaTest {
  static final class Customer {
    String id = "customer-1";
    String dni = "00123456";
    String passport = "AB123456";
  }

  private EncryptedTableSchema<Customer> builder(FieldEncryptor encryptor) {
    return new EncryptedTableSchema<>(Customer.class, Customer::new, encryptor)
        .attribute(
            StaticAttribute.builder(Customer.class, String.class)
                .name("id")
                .getter(item -> item.id)
                .setter((item, value) -> item.id = value)
                .tags(StaticAttributeTags.primaryPartitionKey())
                .build());
  }

  private TableSchema<Customer> schema(FieldEncryptor encryptor) {
    return builder(encryptor)
        .encryptedString("dni", "customer.dni", item -> item.dni, (item, value) -> item.dni = value)
        .encryptedString(
            "passport",
            "customer.passport",
            item -> item.passport,
            (item, value) -> item.passport = value)
        .build();
  }

  @Test
  void effectiveSdkMappingEncryptsEachColumnAndRejectsCiphertextSubstitution() {
    try (var keys = new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey()))) {
      var schema = schema(new FieldEncryptor(keys));
      var item = new Customer();
      var persisted = schema.itemToMap(item, true);
      assertEquals("id", schema.tableMetadata().primaryPartitionKey());
      assertEquals("customer-1", persisted.get("id").s());
      assertNotEquals(item.dni, persisted.get("dni").s());
      assertFalse(persisted.toString().contains(item.dni));
      var read = schema.mapToItem(persisted);
      assertEquals(item.dni, read.dni);
      assertEquals(item.passport, read.passport);
      var replaced = new HashMap<>(persisted);
      replaced.put("passport", persisted.get("dni"));
      assertThrows(CryptoException.class, () -> schema.mapToItem(replaced));
      var wrongType = new HashMap<>(persisted);
      wrongType.put("dni", AttributeValue.builder().n("123456").build());
      assertThrows(IllegalArgumentException.class, () -> schema.mapToItem(wrongType));
    }
  }

  @Test
  void separateSchemasCannotOverwriteKeysAndClosingOneProviderLeavesTheOtherIntact() {
    try (var keys = new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey()))) {
      var first = schema(new FieldEncryptor(keys));
      var stored = first.itemToMap(new Customer(), true);
      try (var otherKeys = new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey()))) {
        var other = schema(new FieldEncryptor(otherKeys));
        assertThrows(CryptoException.class, () -> other.mapToItem(stored));
      }
      assertEquals("00123456", first.mapToItem(stored).dni);
    }
  }

  @Test
  void contextsAndAttributeNamesAreValidatedWhenTheSchemaIsBuilt() {
    try (var keys = new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey()))) {
      var encryptor = new FieldEncryptor(keys);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              builder(encryptor)
                  .encryptedString(
                      "dni", "axiz.field", item -> item.dni, (item, value) -> item.dni = value));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              builder(encryptor)
                  .encryptedString(
                      "id", "customer.dni", item -> item.dni, (item, value) -> item.dni = value));
      var schema =
          builder(encryptor)
              .encryptedString(
                  "dni", "customer.dni", item -> item.dni, (item, value) -> item.dni = value);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              schema.encryptedString(
                  "passport",
                  "customer.dni",
                  item -> item.passport,
                  (item, value) -> item.passport = value));
    }
  }
}
