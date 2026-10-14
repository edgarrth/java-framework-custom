package pe.axiz.core.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EncryptionRegistry;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.id.UuidV7;

class JpaSupportTest {

  static final class Customer extends AuditableEntity {}

  static final class DocumentConverter extends EncryptedConverter {
    @Override
    protected String context() {
      return "customer.document";
    }
  }

  @AfterEach
  void reset() {
    AuditingRegistry.reset();
    EncryptionRegistry.reset();
  }

  @Test
  void entitiesHaveUuidV7AndIdentityEquality() {
    Customer a = new Customer();
    Customer b = new Customer();
    assertTrue(UuidV7.isV7(a.getId()));
    assertTrue(a.isNew());
    assertNotEquals(a, b);
    b.setId(a.getId());
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNull(a.getVersion());
  }

  @Test
  void auditListenerUsesRegisteredAuditorAndClock() {
    Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
    AuditingRegistry.register(() -> Optional.of("user-1"), Clock.fixed(t1, ZoneOffset.UTC));
    Customer c = new Customer();
    AuditListener listener = new AuditListener();
    listener.onCreate(c);
    assertEquals(t1, c.getCreatedAt());
    assertEquals("user-1", c.getCreatedBy());
    Instant t2 = t1.plusSeconds(60);
    AuditingRegistry.register(Optional::empty, Clock.fixed(t2, ZoneOffset.UTC));
    listener.onUpdate(c);
    assertEquals(t1, c.getCreatedAt());
    assertEquals(t2, c.getUpdatedAt());
    assertEquals(AuditListener.SYSTEM, c.getUpdatedBy());
    listener.onCreate("not an entity");
  }

  @Test
  void encryptedConverterRoundTripsWithContext() {
    FieldEncryptor encryptor =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    EncryptionRegistry.register(encryptor, new BlindIndex(new byte[32], 16));
    DocumentConverter converter = new DocumentConverter();
    String column = converter.convertToDatabaseColumn("12345678");
    assertTrue(column.startsWith("axz1:k1:"));
    assertEquals("12345678", converter.convertToEntityAttribute(column));
    assertNull(converter.convertToDatabaseColumn(null));
    assertThrows(
        RuntimeException.class,
        () -> new EncryptedConverter(encryptor).convertToEntityAttribute(column));
    assertEquals(
        EncryptionRegistry.blindIndex("12345678", "customer.document"),
        EncryptionRegistry.blindIndex("1234-5678", "customer.document"));
    EncryptionRegistry.reset();
    assertThrows(
        IllegalStateException.class, () -> new EncryptedConverter().convertToDatabaseColumn("x"));
    assertThrows(IllegalStateException.class, () -> EncryptionRegistry.blindIndex("x", "c"));
  }
}
