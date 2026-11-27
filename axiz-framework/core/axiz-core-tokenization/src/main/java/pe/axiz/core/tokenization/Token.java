package pe.axiz.core.tokenization;

import java.math.BigInteger;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.SelfMasking;

/** Identificador opaco canónico de 128 bits. No es una credencial de autenticación. */
public final class Token implements SelfMasking {
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final BigInteger RADIX = BigInteger.valueOf(62);
  private final String value;

  private Token(String value) {
    this.value = value;
  }

  public static Token parse(String value) {
    if (value == null || !value.matches("tok_[0-9A-Za-z]{22}"))
      throw TokenErrors.failure(TokenErrors.INVALID);
    BigInteger decoded = BigInteger.ZERO;
    for (int i = 4; i < value.length(); i++)
      decoded = decoded.multiply(RADIX).add(BigInteger.valueOf(ALPHABET.indexOf(value.charAt(i))));
    if (decoded.bitLength() > 128) throw TokenErrors.failure(TokenErrors.INVALID);
    return new Token(value);
  }

  /** Codifica exactamente 16 bytes aleatorios suministrados por un generador criptográfico. */
  public static Token fromEntropy(byte[] random) {
    if (random.length != 16) throw TokenErrors.failure(TokenErrors.INVALID);
    BigInteger remaining = new BigInteger(1, random);
    char[] encoded = new char[22];
    for (int i = encoded.length - 1; i >= 0; i--) {
      BigInteger[] division = remaining.divideAndRemainder(RADIX);
      encoded[i] = ALPHABET.charAt(division[1].intValueExact());
      remaining = division[0];
    }
    return new Token("tok_" + new String(encoded));
  }

  /**
   * Acceso explícito para transporte o almacenamiento; los serializadores genéricos deben usar
   * masked().
   */
  public String reveal() {
    return value;
  }

  @Override
  public String masked() {
    return "tok_******************" + value.substring(value.length() - 4);
  }

  @Override
  public String toString() {
    return masked();
  }

  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof Token token && value.equals(token.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }
}
