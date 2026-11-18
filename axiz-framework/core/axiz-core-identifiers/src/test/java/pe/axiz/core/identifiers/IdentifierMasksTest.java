package pe.axiz.core.identifiers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pe.axiz.core.identifiers.contact.ContactIdentifiers;

class IdentifierMasksTest {
  @Test
  void shortOrMalformedValuesNeverExposeTheirOriginalValue() {
    for (String value : new String[] {"", "1", "1234567"}) {
      assertEquals("****", IdentifierMasks.tail(value, 4));
      assertEquals("****", IdentifierMasks.card(value));
    }
    assertEquals("****", IdentifierMasks.email("malformed"));
    assertEquals("****", IdentifierMasks.email("a@b@example.com"));
    assertEquals("****@example.com", ContactIdentifiers.EMAIL.mask("ana@example.com"));
    assertEquals("a***@example.com", ContactIdentifiers.EMAIL.mask("anna@example.com"));
    assertEquals("******78", IdentifierMasks.tail("12345678", 4));
    assertEquals("****", IdentifierMasks.card("1".repeat(20)));
  }
}
