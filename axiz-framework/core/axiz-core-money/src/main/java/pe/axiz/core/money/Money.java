package pe.axiz.core.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;

/**
 * Monto monetario en una moneda ISO 4217. El monto siempre tiene la cantidad de decimales de la
 * moneda (PEN y USD: 2), como máximo {@link #MAX_INTEGER_DIGITS} dígitos enteros, y la aritmética
 * nunca mezcla monedas. Todas las fábricas y operaciones preservan estas invariantes. Inmutable y
 * thread-safe.
 *
 * <p>La forma de texto es {@code "PEN 150.00"}; los adaptadores JSON serializan el monto como
 * cadena para evitar el punto flotante binario en los clientes.
 */
public final class Money implements Comparable<Money> {

  /**
   * Máximo de dígitos aceptados antes del punto decimal (muy por encima de cualquier monto real).
   */
  public static final int MAX_INTEGER_DIGITS = 30;

  /** Máximo de decimales aceptados en un valor antes de redondearlo a la escala de la moneda. */
  public static final int MAX_SCALE = 100;

  /**
   * Longitud máxima del texto externo, verificada antes de la normalización o del parseo decimal.
   */
  public static final int MAX_TEXT_LENGTH = 256;

  static final int MAX_CURRENCY_SCALE = 4;
  static final int PERCENTAGE_SHIFT = 2;
  static final int MAX_PRODUCT_SCALE = MAX_SCALE + MAX_CURRENCY_SCALE + PERCENTAGE_SHIFT;

  private final BigDecimal amount;
  private final Currency currency;

  private Money(BigDecimal amount, Currency currency) {
    requireBounded(amount);
    this.amount = amount;
    this.currency = currency;
  }

  /**
   * Crea un monto redondeado a la escala de la moneda con el modo de redondeo indicado.
   *
   * @param amount monto
   * @param currency moneda
   * @param rounding modo de redondeo
   * @return el monto
   */
  public static Money rounded(BigDecimal amount, Currency currency, RoundingMode rounding) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    requireBounded(amount);
    Objects.requireNonNull(rounding, "rounding");
    int scale = scaleOf(currency);
    return new Money(amount.setScale(scale, rounding), currency);
  }

  /**
   * Crea un monto que ya se ajusta a la escala de la moneda, para valores recibidos de fuera
   * (peticiones, mensajes, archivos): {@code 150.5} se convierte en {@code 150.50}, pero {@code
   * 150.005} se rechaza en lugar de redondearse, de modo que nunca se cobra a un cliente un monto
   * que no envió.
   *
   * @param amount monto
   * @param currencyCode código ISO 4217
   * @return el monto
   * @throws BusinessException {@link MoneyErrors#INVALID_AMOUNT} cuando el monto tiene más
   *     decimales que la moneda o está fuera de rango
   */
  public static Money of(BigDecimal amount, String currencyCode) {
    return of(amount, currency(currencyCode));
  }

  /** Igual que {@link #of(BigDecimal, String)} con una moneda. */
  public static Money of(BigDecimal amount, Currency currency) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    requireBounded(amount);
    try {
      return new Money(amount.setScale(scaleOf(currency), RoundingMode.UNNECESSARY), currency);
    } catch (ArithmeticException e) {
      throw new BusinessException(
          MoneyErrors.INVALID_AMOUNT,
          "too many decimals",
          Map.of("currency", currency.getCurrencyCode(), "decimals", scaleOf(currency)));
    }
  }

  /** Igual que {@link #of(BigDecimal, String)} a partir de texto. */
  public static Money of(String amount, String currencyCode) {
    return of(parseAmount(amount), currencyCode);
  }

  /**
   * Rechaza valores que ningún monto real alcanza antes de cualquier reescalado: reescalar un valor
   * como {@code 1e99999999} o {@code 1e-99999999} requiere una potencia de diez con esa cantidad de
   * dígitos y mantendría un hilo ocupado durante minutos. Los límites dejan margen para resultados
   * intermedios (tasas, porcentajes).
   */
  static void requireBounded(BigDecimal amount) {
    int scale = amount.scale();
    if (scale > MAX_SCALE || scale < -MAX_INTEGER_DIGITS) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "amount out of range");
    }
    if (amount.signum() != 0 && amount.precision() - scale > MAX_INTEGER_DIGITS) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "amount out of range");
    }
  }

  public static Money zero(Currency currency) {
    return of(BigDecimal.ZERO, currency);
  }

  public static Money zero(String currencyCode) {
    return zero(currency(currencyCode));
  }

  /** Unidad mínima (céntimos) a monto, por ejemplo {@code ofMinor(15000, "PEN")} es PEN 150.00. */
  public static Money ofMinor(long minorUnits, String currencyCode) {
    Currency c = currency(currencyCode);
    return new Money(BigDecimal.valueOf(minorUnits, scaleOf(c)), c);
  }

  /**
   * Parsea {@code "PEN 150.00"} (la forma de texto que produce {@link #toString()}). El monto debe
   * ajustarse a la escala de la moneda, como en {@link #of(BigDecimal, String)}: el texto viene de
   * fuera (JSON, valores almacenados) y nunca se redondea.
   *
   * @param text forma de texto
   * @return el monto
   */
  public static Money parse(String text) {
    String checked = requireBoundedText(text);
    String[] parts = checked.strip().split("\\s+", -1);
    if (parts.length != 2) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "unparseable money");
    }
    return of(parts[1], parts[0]);
  }

  private static BigDecimal parseAmount(@Nullable String amount) {
    String checked = requireBoundedText(amount);
    try {
      return new BigDecimal(checked.strip());
    } catch (NumberFormatException e) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid amount");
    }
  }

  private static String requireBoundedText(@Nullable String text) {
    if (text == null || text.length() > MAX_TEXT_LENGTH) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid amount");
    }
    return text;
  }

  private static Currency currency(@Nullable String code) {
    if (code == null) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid currency");
    }
    try {
      return Currency.getInstance(code.strip());
    } catch (IllegalArgumentException e) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid currency");
    }
  }

  static int scaleOf(Currency currency) {
    return checkedCurrencyScale(currency.getDefaultFractionDigits());
  }

  static int checkedCurrencyScale(int digits) {
    if (digits == -1) {
      return 2;
    }
    if (digits < 0 || digits > MAX_CURRENCY_SCALE) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "unsupported currency scale");
    }
    return digits;
  }

  public BigDecimal amount() {
    return amount;
  }

  public Currency currency() {
    return currency;
  }

  public String currencyCode() {
    return currency.getCurrencyCode();
  }

  /** Monto en unidades menores (céntimos); falla si no cabe en un long. */
  public long minorUnits() {
    return amount.movePointRight(amount.scale()).longValueExact();
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  public Money multiply(BigDecimal factor, RoundingMode rounding) {
    return multiplyTo(factor, currency, rounding);
  }

  Money multiplyTo(BigDecimal factor, Currency target, RoundingMode rounding) {
    return scaledProduct(factor, target, rounding, 0);
  }

  private Money scaledProduct(
      BigDecimal operand, Currency target, RoundingMode rounding, int decimalShift) {
    Objects.requireNonNull(rounding, "rounding");
    requireBounded(Objects.requireNonNull(operand, "operand"));
    scaleOf(Objects.requireNonNull(target, "target"));
    return roundedProduct(amount.multiply(operand.movePointLeft(decimalShift)), target, rounding);
  }

  /** Redondea solo los productos internos cuyos operandos externos ya se acotaron. */
  static Money roundedProduct(BigDecimal product, Currency currency, RoundingMode rounding) {
    if (product.scale() > MAX_PRODUCT_SCALE || product.scale() < -MAX_INTEGER_DIGITS) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "amount out of range");
    }
    return new Money(product.setScale(scaleOf(currency), rounding), currency);
  }

  public Money divide(BigDecimal divisor, RoundingMode rounding) {
    // Se acota el operando antes de que BigDecimal pueda expandir una potencia de diez
    // arbitrariamente grande.
    requireBounded(Objects.requireNonNull(divisor, "divisor"));
    if (divisor.signum() == 0) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "divisor must not be zero");
    }
    return new Money(amount.divide(divisor, amount.scale(), rounding), currency);
  }

  /** Aplica un porcentaje con la política de redondeo de quien llama, por ejemplo 18 para 18 %. */
  public Money percentage(BigDecimal percent, RoundingMode rounding) {
    return scaledProduct(percent, currency, rounding, PERCENTAGE_SHIFT);
  }

  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  public Money abs() {
    return amount.signum() < 0 ? negate() : this;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isGreaterThan(Money other) {
    return compareTo(other) > 0;
  }

  public boolean isLessThan(Money other) {
    return compareTo(other) < 0;
  }

  /**
   * Divide el monto en {@code parts} montos que suman exactamente este; las unidades menores
   * sobrantes van a las primeras partes.
   *
   * @param parts cantidad de partes
   * @return las partes
   */
  public List<Money> allocate(int parts) {
    if (parts <= 0) {
      throw new IllegalArgumentException("parts must be positive");
    }
    int[] ratios = new int[parts];
    Arrays.fill(ratios, 1);
    return allocate(ratios);
  }

  /**
   * Divide el monto en proporción a los ratios sin perder unidades menores.
   *
   * @param ratios ratios positivos
   * @return un monto por ratio, que en total suman este monto
   */
  public List<Money> allocate(int... ratios) {
    if (ratios.length == 0) {
      throw new IllegalArgumentException("ratios required");
    }
    long total = 0;
    for (int r : ratios) {
      if (r < 0) {
        throw new IllegalArgumentException("ratios must not be negative");
      }
      total += r;
    }
    if (total == 0) {
      throw new IllegalArgumentException("ratios must not all be zero");
    }
    // aritmética entera exacta: minor * ratio puede superar un long para montos o ratios grandes
    BigInteger minor = amount.unscaledValue();
    BigInteger divisor = BigInteger.valueOf(total);
    BigInteger remainder = minor;
    List<BigInteger> shares = new ArrayList<>();
    for (int r : ratios) {
      BigInteger share = floorDiv(minor.multiply(BigInteger.valueOf(r)), divisor);
      shares.add(share);
      remainder = remainder.subtract(share);
    }
    // la división entera por defecto deja 0 <= resto < cantidad de ratios positivos: una unidad a
    // cada uno, en orden
    List<Money> out = new ArrayList<>();
    for (int i = 0; i < shares.size(); i++) {
      BigInteger value = shares.get(i);
      if (remainder.signum() > 0 && ratios[i] > 0) {
        value = value.add(BigInteger.ONE);
        remainder = remainder.subtract(BigInteger.ONE);
      }
      out.add(new Money(new BigDecimal(value, amount.scale()), currency));
    }
    return out;
  }

  private static BigInteger floorDiv(BigInteger dividend, BigInteger divisor) {
    BigInteger[] qr = dividend.divideAndRemainder(divisor);
    return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new BusinessException(
          MoneyErrors.CURRENCY_MISMATCH,
          "currency mismatch",
          Map.of("expected", currencyCode(), "actual", other.currencyCode()));
    }
  }

  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof Money m && currency.equals(m.currency) && amount.compareTo(m.amount) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(currency, amount.stripTrailingZeros());
  }

  /** Texto plano del monto sin exponente, por ejemplo {@code "150.00"}. */
  public String amountText() {
    return amount.toPlainString();
  }

  @Override
  public String toString() {
    return currencyCode() + " " + amountText();
  }
}
