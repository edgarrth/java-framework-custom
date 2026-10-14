package pe.axiz.core.identifiers;

import java.math.BigInteger;

/** Check digit algorithms shared by identifier types. */
public final class CheckDigits {

  private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

  private CheckDigits() {}

  public static boolean allDigits(String value) {
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

  /** Luhn (mod 10) validation over the full number including its check digit. */
  public static boolean luhn(String digits) {
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

  /** ISO 7064 MOD 97-10 as used by IBAN (value already rearranged and converted). */
  public static int mod97(String numeric) {
    return new BigInteger(numeric).mod(NINETY_SEVEN).intValue();
  }

  /**
   * Weighted modulo-11 check digit: {@code 11 - (sum(d_i * w_i) mod 11)}, where 10 maps to 0 and 11
   * maps to 1.
   *
   * @param digits digits without check digit
   * @param weights weights, same length as digits
   * @return the check digit
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
   * Alternating 1-2 weighted check digit with digit sum of products (the scheme used for the
   * control digits of interbank account codes).
   *
   * @param digits digits without check digit
   * @return the check digit
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
