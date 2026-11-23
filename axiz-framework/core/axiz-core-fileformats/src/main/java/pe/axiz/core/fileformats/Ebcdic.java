package pe.axiz.core.fileformats;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.Charset;

/**
 * Conversión de texto EBCDIC y decimal empaquetado (COMP-3) para archivos intercambiados con
 * mainframes. Página de códigos por defecto IBM037 (EE. UU./Canadá); IBM1047 e IBM284 son
 * alternativas comunes.
 */
public final class Ebcdic {

  public static final Charset IBM037 = Charset.forName("IBM037");

  private Ebcdic() {}

  public static String decode(byte[] bytes, Charset codePage) {
    return new String(bytes, codePage);
  }

  public static byte[] encode(String text, Charset codePage) {
    return text.getBytes(codePage);
  }

  /**
   * Decodifica un decimal empaquetado: dos dígitos por byte, signo en el último nibble (C/F
   * positivo, D negativo).
   */
  public static BigDecimal unpack(byte[] packed, int scale) {
    if (packed.length == 0) {
      throw new IllegalArgumentException("Empty packed decimal");
    }
    StringBuilder digits = new StringBuilder(packed.length * 2);
    for (int i = 0; i < packed.length; i++) {
      int high = (packed[i] >> 4) & 0x0F;
      int low = packed[i] & 0x0F;
      digits.append(digit(high));
      if (i < packed.length - 1) {
        digits.append(digit(low));
      } else {
        BigDecimal value = new BigDecimal(new BigInteger(digits.toString()), scale);
        return switch (low) {
          case 0x0D, 0x0B -> value.negate();
          case 0x0C, 0x0F, 0x0A, 0x0E -> value;
          default -> throw new IllegalArgumentException("Invalid packed decimal sign");
        };
      }
    }
    throw new IllegalStateException("unreachable");
  }

  /** Codifica un decimal empaquetado de la longitud en bytes indicada. */
  public static byte[] pack(BigDecimal value, int scale, int byteLength) {
    BigInteger unscaled = value.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue();
    String digits = unscaled.abs().toString();
    int maxDigits = byteLength * 2 - 1;
    if (digits.length() > maxDigits) {
      throw new IllegalArgumentException("Value does not fit in " + byteLength + " packed bytes");
    }
    digits = "0".repeat(maxDigits - digits.length()) + digits;
    byte[] out = new byte[byteLength];
    for (int i = 0; i < byteLength; i++) {
      int high = digits.charAt(i * 2) - '0';
      int low =
          i == byteLength - 1
              ? (unscaled.signum() < 0 ? 0x0D : 0x0C)
              : digits.charAt(i * 2 + 1) - '0';
      out[i] = (byte) ((high << 4) | low);
    }
    return out;
  }

  private static int digit(int nibble) {
    if (nibble > 9) {
      throw new IllegalArgumentException("Invalid packed decimal digit");
    }
    return nibble;
  }
}
