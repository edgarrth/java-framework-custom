package pe.axiz.kit.jpa;

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
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.core.id.UuidV7;

class JpaSupportTest {

  static final class Customer extends AuditableEntity {}

  static class Account extends AuditableEntity {}

  /** Una subclase es otro tipo de entidad, salvo que sea un proxy generado. */
  static final class SavingsAccount extends Account {}

  static final class DocumentConverter extends EncryptedConverter {
    DocumentConverter(FieldEncryptor encryptor) {
      super(encryptor);
    }

    @Override
    protected String context() {
      return "customer.document";
    }
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
  void equalityIsByIdWithinOneEntityType() {
    Account account = new Account();
    Account same = new Account();
    same.setId(account.getId());
    assertEquals(account, account);
    assertEquals(account, same);

    SavingsAccount savings = new SavingsAccount();
    savings.setId(account.getId());
    assertNotEquals(account, savings, "a subclass that is not a proxy is another type");
    Customer customer = new Customer();
    customer.setId(account.getId());
    assertNotEquals(account, customer, "same id, other entity type");
    assertNotEquals(account, null);
    assertNotEquals(account, new Object());
    assertThrows(NullPointerException.class, () -> account.setId(null));
  }

  @Test
  void auditListenerUsesInjectedAuditorAndClock() {
    Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
    Customer c = new Customer();
    AuditListener listener =
        new AuditListener(() -> Optional.of("user-1"), Clock.fixed(t1, ZoneOffset.UTC));
    listener.onCreate(c);
    assertEquals(t1, c.getCreatedAt());
    assertEquals("user-1", c.getCreatedBy());
    Instant t2 = t1.plusSeconds(60);
    AuditListener later = new AuditListener(Optional::empty, Clock.fixed(t2, ZoneOffset.UTC));
    later.onUpdate(c);
    assertEquals(t1, c.getCreatedAt());
    assertEquals(t2, c.getUpdatedAt());
    assertEquals(AuditListener.UNATTRIBUTED, c.getUpdatedBy());
    new AuditListener(() -> Optional.of(" "), Clock.fixed(t2, ZoneOffset.UTC)).onUpdate(c);
    assertEquals(AuditListener.UNATTRIBUTED, c.getUpdatedBy());
    Customer independent = new Customer();
    listener.onCreate(independent);
    assertEquals("user-1", independent.getCreatedBy());
    assertEquals(t1, independent.getCreatedAt());
    listener.onCreate("not an entity");
    listener.onUpdate("not an entity");
  }

  @Test
  void encryptedConverterRoundTripsWithContext() {
    FieldEncryptor encryptor =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    DocumentConverter converter = new DocumentConverter(encryptor);
    String column = converter.convertToDatabaseColumn("12345678");
    assertTrue(column.startsWith("axz1:k1:"));
    assertEquals("12345678", converter.convertToEntityAttribute(column));
    assertNull(converter.convertToDatabaseColumn(null));
    assertThrows(
        RuntimeException.class,
        () ->
            new EncryptedConverter(encryptor) {
              protected String context() {
                return "other.document";
              }
            }.convertToEntityAttribute(column));
    FieldEncryptor other =
        new FieldEncryptor(new StaticKeyProvider("k1", Map.of("k1", AesGcm.newKey())));
    assertThrows(
        RuntimeException.class,
        () -> new DocumentConverter(other).convertToEntityAttribute(column));
  }
}
