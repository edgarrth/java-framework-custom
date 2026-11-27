package pe.axiz.core.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.time.YearMonth;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;

class CardDataTest {
  @Test
  void panValidationMaskingAndSerializationNeverExposeFullValue() throws Exception {
    String raw = "4111111111111111";
    Pan pan = Pan.parse("4111-1111 1111-1111");
    assertEquals(raw, pan.reveal());
    assertEquals("411111******1111", pan.masked());
    assertEquals(pan.masked(), pan.toString());
    assertEquals(pan.masked(), String.format("%s", pan));
    assertEquals(pan.masked(), ObjectMasker.defaults().describe(pan, OutputChannel.LOG));
    assertEquals(pan.masked(), TruncatedPan.of(pan).toString());
    assertEquals(pan, Pan.parse(raw));
    assertEquals(pan.hashCode(), Pan.parse(raw).hashCode());
    try (var out = new ObjectOutputStream(new ByteArrayOutputStream())) {
      assertThrows(NotSerializableException.class, () -> out.writeObject(pan));
    }
    for (String invalid : new String[] {"4111111111111112", "x", "1".repeat(65), ""}) {
      BusinessException error = assertThrows(BusinessException.class, () -> Pan.parse(invalid));
      assertFalse(error.toString().contains("4111111111111112"));
      assertFalse(Pan.looksLikePan(invalid));
    }
    for (int length = 12; length <= 19; length++) {
      String prefix = "4" + "0".repeat(length - 2);
      String valid = null;
      for (int check = 0; check < 10; check++)
        if (CheckDigits.luhn(prefix + check)) valid = prefix + check;
      assertTrue(Pan.looksLikePan(valid));
      assertEquals(length, Pan.parse(valid).reveal().length());
    }
  }

  @Test
  void authenticationDataHasIdentityAndOwnedDisposableBuffers() throws Exception {
    var code = CardVerificationCode.parse("123");
    assertNotEquals(code, CardVerificationCode.parse("123"));
    assertEquals("***", code.toString());
    char[][] escaped = new char[1][];
    assertEquals(
        "123",
        code.use(
            chars -> {
              escaped[0] = chars;
              return new String(chars);
            }));
    assertTrue(Arrays.equals(new char[3], escaped[0]));
    assertThrows(
        IllegalStateException.class,
        () ->
            code.use(
                chars -> {
                  escaped[0] = chars;
                  throw new IllegalStateException("test");
                }));
    assertTrue(Arrays.equals(new char[3], escaped[0]));
    code.close();
    code.close();
    assertThrows(IllegalStateException.class, () -> code.use(String::new));
    try (var out = new ObjectOutputStream(new ByteArrayOutputStream());
        var pin = Pin.parse("0123")) {
      assertThrows(NotSerializableException.class, () -> out.writeObject(pin));
      assertEquals("***", pin.toString());
      assertEquals("0123", pin.use(String::new));
    }
    for (String invalid : new String[] {"12", "12345", "12x"})
      assertThrows(BusinessException.class, () -> CardVerificationCode.parse(invalid));
    for (String invalid : new String[] {"123", "1234567890123", "xxxx"})
      assertThrows(BusinessException.class, () -> Pin.parse(invalid));
  }

  @Test
  void expiryUsesAnExplicitClockAndTruncatedValuesCannotInventInvalidLengths() {
    CardExpiry expiry = CardExpiry.parse("11/26");
    assertFalse(expiry.isExpiredAt(YearMonth.of(2026, 11)));
    assertTrue(expiry.isExpiredAt(YearMonth.of(2026, 12)));
    assertEquals("11/26", expiry.formatted());
    for (String invalid : new String[] {"13/26", "00/26", "1/26", "11/2026"})
      assertThrows(BusinessException.class, () -> CardExpiry.parse(invalid));
    assertThrows(BusinessException.class, () -> new TruncatedPan("4", "1111", 16));
    assertThrows(BusinessException.class, () -> new TruncatedPan("411111", "1111", 20));
  }
}
