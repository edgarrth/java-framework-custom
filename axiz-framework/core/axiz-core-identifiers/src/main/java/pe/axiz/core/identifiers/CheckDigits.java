package pe.axiz.core.identifiers;

import java.math.BigInteger;

/** Algoritmos de dígito verificador compartidos por los tipos de identificador. */
public final class CheckDigits {

  private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

  private CheckDigits() {}

  public static boolean allDigits(String value) {
    return allDigits((CharSequence) value);
  }

  /** Valida un almacenamiento de caracteres prestado sin crear una copia inmutable. */
  public static boolean allDigits(CharSequence value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** Validación Luhn (módulo 10) sobre el número completo, incluido su dígito verificador. */
  public static boolean luhn(String digits) {
    return luhn((CharSequence) digits);
  }

  /** Luhn sobre un almacenamiento de caracteres prestado, incluido su dígito verificador. */
  public static boolean luhn(CharSequence digits) {
    if (!allDigits(digits)) {
      return false;
    }
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubleIt) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }

  /** ISO 7064 MOD 97-10 tal como lo usa IBAN (valor ya reordenado y convertido). */
  public static int mod97(String numeric) {
    return new BigInteger(numeric).mod(NINETY_SEVEN).intValue();
  }

  /**
   * Dígito verificador módulo 11 ponderado: {@code 11 - (sum(d_i * w_i) mod 11)}, donde 10 se mapea
   * a 0 y 11 se mapea a 1.
   *
   * @param digits dígitos sin el dígito verificador
   * @param weights pesos, de la misma longitud que los dígitos
   * @return el dígito verificador
   */
  public static int mod11(String digits, int[] weights) {
    if (digits.length() != weights.length) {
      throw new IllegalArgumentException("digits and weights length differ");
    }
    int sum = 0;
    for (int i = 0; i < digits.length(); i++) {
      sum += (digits.charAt(i) - '0') * weights[i];
    }
    int check = 11 - (sum % 11);
    return check == 10 ? 0 : (check == 11 ? 1 : check);
  }

  /**
   * Dígito verificador con pesos alternos 1-2 y suma de los dígitos de los productos (el esquema
   * usado para los dígitos de control de los códigos de cuenta interbancarios).
   *
   * @param digits dígitos sin el dígito verificador
   * @return el dígito verificador
   */
  public static int alternatingWeights(String digits) {
    int sum = 0;
    for (int i = 0; i < digits.length(); i++) {
      int product = (digits.charAt(i) - '0') * (i % 2 == 0 ? 1 : 2);
      sum += product > 9 ? product / 10 + product % 10 : product;
    }
    int mod = sum % 10;
    return mod == 0 ? 0 : 10 - mod;
  }
}
