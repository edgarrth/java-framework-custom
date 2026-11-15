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
 * Monetary amount in an ISO 4217 currency. The amount always has the currency's number of decimals
 * (PEN and USD: 2), at most {@link #MAX_INTEGER_DIGITS} integer digits, and arithmetic never mixes
 * currencies. Every factory and operation preserves these invariants. Immutable and thread-safe.
 *
 * <p>Text form is {@code "PEN 150.00"}; JSON adapters serialize the amount as a string to avoid
 * binary floating point in clients.
 */
public final class Money implements Comparable<Money> {

  /** Most digits accepted before the decimal point (far above any real amount). */
  public static final int MAX_INTEGER_DIGITS = 30;

  /** Most decimals accepted in a value before it is rounded to the currency scale. */
  public static final int MAX_SCALE = 100;

  /** Maximum external text length, checked before normalization or decimal parsing. */
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
   * Creates an amount rounded to the currency scale with the given rounding mode.
   *
   * @param amount amount
   * @param currency currency
   * @param rounding rounding mode
   * @return the money
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
   * Creates an amount that already fits the currency scale, for values received from outside
   * (requests, messages, files): {@code 150.5} becomes {@code 150.50}, but {@code 150.005} is
   * rejected instead of being rounded, so a client is never charged an amount it did not send.
   *
   * @param amount amount
   * @param currencyCode ISO 4217 code
   * @return the money
   * @throws BusinessException {@link MoneyErrors#INVALID_AMOUNT} when the amount has more decimals
   *     than the currency or is out of range
   */
  public static Money of(BigDecimal amount, String currencyCode) {
    return of(amount, currency(currencyCode));
  }

  /** Same as {@link #of(BigDecimal, String)} with a currency. */
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

  /** Same as {@link #of(BigDecimal, String)} from text. */
  public static Money of(String amount, String currencyCode) {
    return of(parseAmount(amount), currencyCode);
  }

  /**
   * Rejects values no real amount reaches before any rescaling: rescaling a value such as {@code
   * 1e99999999} or {@code 1e-99999999} needs a power of ten with that many digits and would keep a
   * thread busy for minutes. The limits leave room for intermediate results (rates, percentages).
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

  /** Smallest unit (cents) to money, e.g. {@code ofMinor(15000, "PEN")} is PEN 150.00. */
  public static Money ofMinor(long minorUnits, String currencyCode) {
    Currency c = currency(currencyCode);
    return new Money(BigDecimal.valueOf(minorUnits, scaleOf(c)), c);
  }

  /**
   * Parses {@code "PEN 150.00"} (the text form produced by {@link #toString()}). The amount must
   * fit the currency scale, as in {@link #of(BigDecimal, String)}: text comes from outside (JSON,
   * stored values) and is never rounded.
   *
   * @param text text form
   * @return the money
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

  /** Amount in minor units (cents); fails if it does not fit in a long. */
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

  /** Rounds only internal products whose external operands have already been bounded. */
  static Money roundedProduct(BigDecimal product, Currency currency, RoundingMode rounding) {
    if (product.scale() > MAX_PRODUCT_SCALE || product.scale() < -MAX_INTEGER_DIGITS) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "amount out of range");
    }
    return new Money(product.setScale(scaleOf(currency), rounding), currency);
  }

  public Money divide(BigDecimal divisor, RoundingMode rounding) {
    // Bound the operand before BigDecimal can expand an arbitrarily large power of ten.
    requireBounded(Objects.requireNonNull(divisor, "divisor"));
    return new Money(amount.divide(divisor, amount.scale(), rounding), currency);
  }

  /** Applies a percentage with the caller's rounding policy, e.g. 18 for 18 %. */
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
   * Splits the amount into {@code parts} amounts that add up exactly to this one; leftover minor
   * units go to the first parts.
   *
   * @param parts number of parts
   * @return the parts
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
   * Splits the amount proportionally to the ratios without losing minor units.
   *
   * @param ratios positive ratios
   * @return one amount per ratio, adding up to this amount
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
    // exact integer arithmetic: minor * ratio can exceed a long for large amounts or ratios
    BigInteger minor = amount.unscaledValue();
    BigInteger divisor = BigInteger.valueOf(total);
    BigInteger remainder = minor;
    List<BigInteger> shares = new ArrayList<>();
    for (int r : ratios) {
      BigInteger share = floorDiv(minor.multiply(BigInteger.valueOf(r)), divisor);
      shares.add(share);
      remainder = remainder.subtract(share);
    }
    // floor division leaves 0 <= remainder < number of positive ratios: one unit each, in order
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

  /** Plain amount text without exponent, e.g. {@code "150.00"}. */
  public String amountText() {
    return amount.toPlainString();
  }

  @Override
  public String toString() {
    return currencyCode() + " " + amountText();
  }
}
