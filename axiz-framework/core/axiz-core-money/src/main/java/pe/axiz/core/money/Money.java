package pe.axiz.core.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;

/**
 * Monetary amount in an ISO 4217 currency. The amount always has the currency's number of decimals
 * (PEN and USD: 2) and arithmetic never mixes currencies. Immutable and thread-safe.
 *
 * <p>Text form is {@code "PEN 150.00"}; JSON adapters serialize the amount as a string to avoid
 * binary floating point in clients.
 */
public final class Money implements Comparable<Money> {

  /** Default rounding: banker's rounding, unbiased for accumulated amounts. */
  public static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_EVEN;

  private final BigDecimal amount;
  private final Currency currency;

  private Money(BigDecimal amount, Currency currency) {
    this.amount = amount;
    this.currency = currency;
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return of(amount, currency, DEFAULT_ROUNDING);
  }

  /**
   * Creates an amount rounded to the currency scale with the given rounding mode.
   *
   * @param amount amount
   * @param currency currency
   * @param rounding rounding mode
   * @return the money
   */
  public static Money of(BigDecimal amount, Currency currency, RoundingMode rounding) {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    int scale = scaleOf(currency);
    return new Money(amount.setScale(scale, rounding), currency);
  }

  public static Money of(String amount, String currencyCode) {
    return of(parseAmount(amount), currency(currencyCode));
  }

  public static Money of(BigDecimal amount, String currencyCode) {
    return of(amount, currency(currencyCode));
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
   * Parses {@code "PEN 150.00"} (the text form produced by {@link #toString()}).
   *
   * @param text text form
   * @return the money
   */
  public static Money parse(String text) {
    String[] parts = text == null ? new String[0] : text.strip().split("\\s+", -1);
    if (parts.length != 2) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "unparseable money");
    }
    return of(parts[1], parts[0]);
  }

  private static BigDecimal parseAmount(@Nullable String amount) {
    if (amount == null) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid amount");
    }
    try {
      return new BigDecimal(amount.strip());
    } catch (NumberFormatException e) {
      throw new BusinessException(MoneyErrors.INVALID_AMOUNT, "invalid amount");
    }
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

  private static int scaleOf(Currency currency) {
    int digits = currency.getDefaultFractionDigits();
    return digits < 0 ? 2 : digits;
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

  public Money multiply(BigDecimal factor) {
    return multiply(factor, DEFAULT_ROUNDING);
  }

  public Money multiply(BigDecimal factor, RoundingMode rounding) {
    return of(amount.multiply(factor), currency, rounding);
  }

  public Money divide(BigDecimal divisor, RoundingMode rounding) {
    return new Money(amount.divide(divisor, amount.scale(), rounding), currency);
  }

  /** Applies a percentage, e.g. {@code percentage(new BigDecimal("18"))} for 18 %. */
  public Money percentage(BigDecimal percent) {
    return multiply(percent.movePointLeft(2));
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
    java.util.Arrays.fill(ratios, 1);
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
