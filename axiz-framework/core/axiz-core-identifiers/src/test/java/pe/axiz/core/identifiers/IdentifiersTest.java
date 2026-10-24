package pe.axiz.core.identifiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pe.axiz.core.identifiers.banking.BankingIdentifiers;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;

class IdentifiersTest {

  private final IdentifierRegistry registry = IdentifierRegistry.withDiscoveredTypes();

  @Test
  void discoversAllPacks() {
    assertTrue(
        registry
            .names()
            .containsAll(
                List.of(
                    "dni", "ruc", "ce", "cci", "cci-strict", "card", "iban", "phone", "email")));
  }

  @ParameterizedTest
  @CsvSource({
    "dni, 12345678, true",
    "dni, 1234567, false",
    "dni, 1234567A, false",
    "ruc, 20100070970, true",
    "ruc, 10467812349, true",
    "ruc, 20100070971, false",
    "ruc, 30100070970, false",
    "ce, 001234567, true",
    "ce, 12345, false",
    "cci, 002-191-000123456789-57, true",
    "cci, 0021910001234567895, false",
    "cci-strict, 00219100012345678957, true",
    "cci-strict, 00219100012345678958, false",
    "card, 4111 1111 1111 1111, true",
    "card, 4111 1111 1111 1112, false",
    "card, 378282246310005, true",
    "iban, GB82 WEST 1234 5698 7654 32, true",
    "iban, DE89370400440532013000, true",
    "iban, DE89370400440532013001, false",
    "iban, GB82WEST123456987654321, false",
    "phone, +51 987 654 321, true",
    "phone, 987654321, false",
    "email, Ana.Perez@Example.COM, true",
    "email, ana@@example.com, false"
  })
  void validates(String type, String value, boolean valid) {
    assertEquals(valid, registry.isValid(type, value), type + " " + value);
  }

  @Test
  void parseNormalizesAndMasks() {
    Identifier card = registry.parse("card", "5555-5555-5555-4444");
    assertEquals("5555555555554444", card.value());
    assertEquals("555555******4444", card.masked(registry));
    assertEquals(
        "a********@example.com", registry.parse("email", "Ana.Perez@example.com").masked(registry));
    assertEquals("****5678", registry.parse("dni", "12345678").masked(registry));
    assertEquals("20100070970", registry.parse("ruc", "20100070970").masked(registry));
    assertFalse(card.toString().contains("55555555"));
    InvalidIdentifierException ex =
        assertThrows(InvalidIdentifierException.class, () -> registry.parse("dni", "abc"));
    assertEquals("dni", ex.type());
    assertThrows(InvalidIdentifierException.class, () -> registry.parse("dni", null));
    assertFalse(registry.isValid("dni", " "));
    assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
  }

  @Test
  void cardBrandsAndBin() {
    assertEquals(CardBrand.VISA, CardBrand.guess("4111111111111111"));
    assertEquals(CardBrand.MASTERCARD, CardBrand.guess("5555555555554444"));
    assertEquals(CardBrand.MASTERCARD, CardBrand.guess("2221000000000009"));
    assertEquals(CardBrand.AMEX, CardBrand.guess("378282246310005"));
    assertEquals(CardBrand.DINERS, CardBrand.guess("30569309025904"));
    assertEquals(CardBrand.DISCOVER, CardBrand.guess("6011111111111117"));
    assertEquals(CardBrand.JCB, CardBrand.guess("3530111333300000"));
    assertEquals(CardBrand.UNIONPAY, CardBrand.guess("6200000000000005"));
    assertEquals(CardBrand.UNKNOWN, CardBrand.guess("9999999999999"));
    assertEquals(CardBrand.UNKNOWN, CardBrand.guess("41"));
    assertEquals("41111111", BankingIdentifiers.bin("4111111111111111"));
    assertEquals("378282", BankingIdentifiers.bin("378282246310005"));
  }

  @Test
  void customTypesCanBeRegisteredWithoutTouchingTheLibrary() {
    IdentifierType memberCode =
        new IdentifierType() {
          @Override
          public String name() {
            return "member-code";
          }

          @Override
          public boolean isValid(String v) {
            return v.matches("^M\\d{6}$");
          }
        };
    IdentifierRegistry custom =
        IdentifierRegistry.builder().discover().register(memberCode).build();
    assertTrue(custom.isValid("member-code", "m123456"));
    assertThrows(
        IllegalArgumentException.class,
        () -> IdentifierRegistry.builder().register(memberCode).register(memberCode));
  }

  @Test
  void checkDigitHelpers() {
    assertTrue(CheckDigits.luhn("79927398713"));
    assertFalse(CheckDigits.luhn("7992739871a"));
    assertFalse(CheckDigits.allDigits(""));
    assertThrows(IllegalArgumentException.class, () -> CheckDigits.mod11("123", new int[] {1, 2}));
  }
}
