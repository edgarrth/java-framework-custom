package pe.axiz.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class MoneyTest {

  private static final Currency PEN = Currency.getInstance("PEN");
  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void textFactoriesRejectExcessiveLexicalLengthEvenForAnOtherwiseValidAmount() {
    String padded = "0".repeat(4096) + "1.00";
    assertThrows(BusinessException.class, () -> Money.of(padded, "PEN"));
    assertThrows(BusinessException.class, () -> Money.of(padded, "PEN"));
    assertThrows(BusinessException.class, () -> Money.parse("PEN " + padded));
  }

  @Test
  void textLengthBoundaryPreservesValidAmountsAndRejectsLargeInputsImmediately() {
    String boundary = "0".repeat(Money.MAX_TEXT_LENGTH - 4) + "1.00";
    assertEquals(Money.of("1", "PEN"), Money.of(boundary, "PEN"));
    assertThrows(BusinessException.class, () -> Money.of("0" + boundary, "PEN"));
    String complete = "PEN" + " ".repeat(Money.MAX_TEXT_LENGTH - 4) + "1";
    assertEquals(Money.of("1", "PEN"), Money.parse(complete));
    assertThrows(BusinessException.class, () -> Money.parse(complete + " "));
    String attack = "9".repeat(1_000_000);
    assertTimeout(
        Duration.ofSeconds(2),
        () -> {
          assertThrows(BusinessException.class, () -> Money.of(attack, "PEN"));
          assertThrows(BusinessException.class, () -> Money.of(attack, "PEN"));
          assertThrows(BusinessException.class, () -> Money.parse("PEN " + attack));
        });
  }

  @Test
  void everyFactoryAndArithmeticResultKeepsTheMoneyRange() {
    for (String code : List.of("PEN", "JPY", "KWD")) {
      Money max = Money.of("9".repeat(Money.MAX_INTEGER_DIGITS), code);
      Money one = Money.of("1", code);
      assertThrows(BusinessException.class, () -> max.plus(one));
      assertThrows(BusinessException.class, () -> max.negate().minus(one));
      assertThrows(
          BusinessException.class, () -> max.multiply(new BigDecimal("2"), RoundingMode.HALF_EVEN));
      assertThrows(
          BusinessException.class, () -> max.divide(new BigDecimal("0.1"), RoundingMode.HALF_EVEN));
      assertThrows(
          BusinessException.class,
          () -> max.percentage(new BigDecimal("200"), RoundingMode.HALF_EVEN));
      // Every successful operation still round-trips, including allocations and negative values.
      for (Money value :
          List.of(
              max,
              max.minus(one),
              max.negate(),
              max.negate().abs(),
              max.divide(new BigDecimal("3"), RoundingMode.HALF_EVEN),
              max.percentage(new BigDecimal("10"), RoundingMode.HALF_EVEN),
              Money.ofMinor(Long.MIN_VALUE, code),
              Money.zero(code))) {
        assertEquals(value, Money.parse(value.toString()));
        for (Money share : value.allocate(2, 3, 5)) {
          assertEquals(share, Money.parse(share.toString()));
        }
      }
    }
    for (String sign : List.of("", "-")) {
      assertThrows(
          BusinessException.class,
          () -> Money.of(sign + "9".repeat(Money.MAX_INTEGER_DIGITS) + ".995", "PEN"));
    }
  }

  @Test
  void arithmeticRejectsUnboundedOperandsBeforeExpandingPowersOfTen() {
    Money one = Money.of("1", "PEN");
    for (String value : List.of("1e99999999", "1e-99999999")) {
      BigDecimal operand = new BigDecimal(value);
      assertThrows(BusinessException.class, () -> one.divide(operand, RoundingMode.HALF_EVEN));
      assertThrows(BusinessException.class, () -> one.percentage(operand, RoundingMode.HALF_EVEN));
      assertThrows(BusinessException.class, () -> one.multiply(operand, RoundingMode.HALF_EVEN));
    }
  }

  @Test
  void scaleAndRoundingFollowCurrency() {
    assertEquals("PEN 150.00", Money.of("150", "PEN").toString());
    assertEquals(
        "PEN 0.12",
        Money.rounded(new BigDecimal("0.125"), Currency.getInstance("PEN"), RoundingMode.HALF_EVEN)
            .toString());
    assertEquals(
        "PEN 0.14",
        Money.rounded(new BigDecimal("0.135"), Currency.getInstance("PEN"), RoundingMode.HALF_EVEN)
            .toString());
    assertEquals(
        "PEN 0.13", Money.rounded(new BigDecimal("0.125"), PEN, RoundingMode.HALF_UP).toString());
    assertEquals(
        "JPY 1000",
        Money.rounded(new BigDecimal("1000.5"), Currency.getInstance("JPY"), RoundingMode.HALF_EVEN)
            .toString());
    assertEquals(
        "JPY 1002",
        Money.rounded(new BigDecimal("1001.5"), Currency.getInstance("JPY"), RoundingMode.HALF_EVEN)
            .toString());
    assertEquals(15000, Money.of("150.00", "PEN").minorUnits());
    assertEquals(Money.of("150.00", "PEN"), Money.ofMinor(15000, "PEN"));
  }

  @Test
  void arithmetic() {
    Money a = Money.of("100.10", "PEN");
    Money b = Money.of("0.20", "PEN");
    assertEquals(Money.of("100.30", "PEN"), a.plus(b));
    assertEquals(Money.of("99.90", "PEN"), a.minus(b));
    assertEquals(
        Money.of("18.02", "PEN"), a.percentage(new BigDecimal("18"), RoundingMode.HALF_EVEN));
    assertEquals(Money.of("33.37", "PEN"), a.divide(new BigDecimal("3"), RoundingMode.HALF_EVEN));
    assertEquals(Money.of("-100.10", "PEN"), a.negate());
    assertEquals(a, a.negate().abs());
    assertTrue(a.isGreaterThan(b));
    assertTrue(b.isLessThan(a));
    assertTrue(Money.zero("PEN").isZero());
    assertTrue(a.isPositive());
    assertTrue(a.negate().isNegative());
    assertEquals(Money.of("1.10", "PEN").hashCode(), Money.of("1.1", "PEN").hashCode());
    assertNotEquals(Money.of("1", "PEN"), Money.of("1", "USD"));
  }

  @Test
  void neverMixesCurrencies() {
    BusinessException ex =
        assertThrows(
            BusinessException.class, () -> Money.of("1", "PEN").plus(Money.of("1", "USD")));
    assertEquals("AXZ-MONEY-0001", ex.code().value());
    assertEquals("USD", ex.parameters().get("actual"));
    assertThrows(
        BusinessException.class, () -> Money.of("1", "PEN").compareTo(Money.of("1", "USD")));
  }

  @Test
  void parsingRejectsGarbage() {
    assertEquals(Money.of("12.50", "USD"), Money.parse("USD 12.50"));
    assertThrows(BusinessException.class, () -> Money.parse("12.50"));
    assertThrows(BusinessException.class, () -> Money.of("abc", "PEN"));
    assertThrows(BusinessException.class, () -> Money.of("1", "XXXX"));
    assertThrows(BusinessException.class, () -> Money.parse(null));
    assertEquals(
        "invalid amount",
        assertThrows(BusinessException.class, () -> Money.of((String) null, "PEN")).getMessage());
    assertEquals(
        "invalid currency",
        assertThrows(BusinessException.class, () -> Money.of("1", null)).getMessage());
    assertEquals(
        "invalid currency",
        assertThrows(BusinessException.class, () -> Money.of("1", "XXXX")).getMessage());
  }

  @Test
  void allocationNeverLosesCents() {
    List<Money> parts = Money.of("100.00", "PEN").allocate(3);
    assertEquals(
        List.of(Money.of("33.34", "PEN"), Money.of("33.33", "PEN"), Money.of("33.33", "PEN")),
        parts);
    List<Money> weighted = Money.of("0.05", "PEN").allocate(3, 7);
    assertEquals(List.of(Money.of("0.02", "PEN"), Money.of("0.03", "PEN")), weighted);
    Money total = Money.zero("PEN");
    for (Money m : Money.of("-10.01", "PEN").allocate(1, 1, 1)) {
      total = total.plus(m);
    }
    assertEquals(Money.of("-10.01", "PEN"), total);
    assertEquals(Money.of("5.00", "PEN"), Money.of("5.00", "PEN").allocate(0, 1).get(1));
    assertThrows(IllegalArgumentException.class, () -> Money.of("1", "PEN").allocate(0));
    assertThrows(IllegalArgumentException.class, () -> Money.of("1", "PEN").allocate(0, 0));
    assertThrows(IllegalArgumentException.class, () -> Money.of("1", "PEN").allocate(-1, 2));
  }

  /** Regression: {@code minor * ratio} overflowed a long and produced wrong parts. */
  @Test
  void allocationOfLargeAmountsWithLargeRatiosDoesNotOverflow() {
    Money big = Money.of("90000000000000000.00", "PEN"); // 9e18 minor units
    List<Money> parts = big.allocate(Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
    Money sum = Money.zero("PEN");
    for (Money m : parts) {
      sum = sum.plus(m);
      assertTrue(m.amount().signum() >= 0, "no part flips sign: " + m);
    }
    assertEquals(big, sum);
    assertTrue(Math.abs(parts.get(0).minorUnits() - parts.get(1).minorUnits()) <= 1);
    assertTrue(parts.get(0).minorUnits() > 4_000_000_000_000_000_000L);
  }

  /** Property: parts add up exactly and each is within one minor unit of its exact share. */
  @Test
  void allocationPropertiesHoldForRandomInputs() {
    Random random = new Random(20261012L);
    for (int run = 0; run < 2_000; run++) {
      long minor = random.nextLong() % 1_000_000_000_000_000L;
      Money amount = Money.ofMinor(minor, "PEN");
      int n = 1 + random.nextInt(6);
      int[] ratios = new int[n];
      long total = 0;
      for (int i = 0; i < n; i++) {
        ratios[i] = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(Integer.MAX_VALUE - 1);
        total += ratios[i];
      }
      if (total == 0) {
        ratios[0] = 1;
        total = 1;
      }
      List<Money> parts = amount.allocate(ratios);
      BigInteger sum = BigInteger.ZERO;
      for (int i = 0; i < n; i++) {
        BigInteger part = BigInteger.valueOf(parts.get(i).minorUnits());
        sum = sum.add(part);
        BigDecimal exact =
            new BigDecimal(BigInteger.valueOf(minor).multiply(BigInteger.valueOf(ratios[i])))
                .divide(BigDecimal.valueOf(total), 10, RoundingMode.HALF_EVEN);
        assertTrue(
            exact.subtract(new BigDecimal(part)).abs().compareTo(BigDecimal.ONE) < 0,
            "part " + i + " of " + minor + " by " + Arrays.toString(ratios));
        if (ratios[i] == 0) {
          assertEquals(0, parts.get(i).minorUnits());
        }
      }
      assertEquals(BigInteger.valueOf(minor), sum);
    }
  }

  @Test
  void conversionWithDirectAndInverseRates() {
    Instant now = Instant.parse("2026-06-01T15:00:00Z");
    ExchangeRateProvider provider =
        (from, to, at) ->
            from.equals(USD) && to.equals(PEN)
                ? Optional.of(new ExchangeRate(USD, PEN, new BigDecimal("3.75"), at))
                : Optional.empty();
    CurrencyConverter converter = new CurrencyConverter(provider, Clock.fixed(now, ZoneOffset.UTC));
    assertEquals(
        Money.of("375.00", "PEN"),
        converter.convert(Money.of("100", "USD"), PEN, RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("100.00", "USD"),
        converter.convert(Money.of("375.00", "PEN"), USD, RoundingMode.HALF_EVEN));
    Money pen = Money.of("1", "PEN");
    assertEquals(pen, converter.convert(pen, PEN, RoundingMode.HALF_EVEN));
    assertThrows(
        BusinessException.class,
        () -> converter.convert(pen, Currency.getInstance("EUR"), RoundingMode.HALF_EVEN));
    assertThrows(
        IllegalArgumentException.class, () -> new ExchangeRate(USD, PEN, BigDecimal.ZERO, now));
    assertFalse(
        new ExchangeRate(USD, PEN, BigDecimal.TEN, now)
                .inverse(10, RoundingMode.HALF_EVEN)
                .rate()
                .signum()
            <= 0);
  }

  @Test
  void tenThousandCentsAddUpExactly() {
    Money total = Money.zero("PEN");
    Money cent = Money.of("0.01", "PEN");
    for (int i = 0; i < 10_000; i++) {
      total = total.plus(cent);
    }
    assertEquals(Money.of("100.00", "PEN"), total);
    assertEquals(0, new BigDecimal("100.00").compareTo(total.amount()));
  }

  @Test
  void exactAmountsFromOutsideAreNeverRounded() {
    assertEquals("PEN 150.50", Money.of(new BigDecimal("150.5"), "PEN").toString());
    assertEquals("PEN 150.00", Money.of("150.000", "PEN").toString());
    assertEquals("JPY 1000", Money.of("1000", "JPY").toString());
    assertThrows(BusinessException.class, () -> Money.of("150.005", "PEN"));
    assertThrows(BusinessException.class, () -> Money.of("1000.5", "JPY"));
    assertThrows(BusinessException.class, () -> Money.parse("PEN 150.005"));
    assertEquals(Money.of("150.50", "PEN"), Money.parse("PEN 150.5"));
    // rounding stays available for computed amounts
    assertEquals(
        "PEN 150.00",
        Money.rounded(new BigDecimal("150.005"), PEN, RoundingMode.HALF_EVEN).toString());
  }

  @Test
  void hugeExponentsAreRejectedBeforeAnyRescaling() {
    long start = System.nanoTime();
    for (String text : List.of("1e99999999", "1e-99999999", "0E+99999999", "0E-99999999")) {
      assertThrows(BusinessException.class, () -> Money.of(text, "PEN"), text);
      assertThrows(
          BusinessException.class,
          () -> Money.rounded(new BigDecimal(text), PEN, RoundingMode.HALF_EVEN),
          text);
      assertThrows(BusinessException.class, () -> Money.parse("PEN " + text), text);
    }
    assertTrue(System.nanoTime() - start < 5_000_000_000L, "rejection must be immediate");
    assertThrows(
        BusinessException.class, () -> Money.of("1" + "0".repeat(Money.MAX_INTEGER_DIGITS), "PEN"));
    assertEquals(
        Money.MAX_INTEGER_DIGITS,
        Money.of("9".repeat(Money.MAX_INTEGER_DIGITS), "PEN").amount().precision() - 2);
    // intermediate results with many decimals are still rounded
    assertEquals(
        "PEN 33.33",
        Money.rounded(
                new BigDecimal("100").divide(new BigDecimal("3"), 50, RoundingMode.HALF_EVEN),
                PEN,
                RoundingMode.HALF_EVEN)
            .toString());
  }

  @Test
  void exactFactoriesRespectAllCurrencyScalesAndExponentBounds() {
    assertEquals("BHD 1.234", Money.of("1.2340", "BHD").toString());
    assertThrows(BusinessException.class, () -> Money.of("1.2345", "BHD"));
    assertThrows(BusinessException.class, () -> Money.of("10.005", "PEN"));
    assertThrows(BusinessException.class, () -> Money.of("100.5", "JPY"));
    assertEquals("PEN 100.00", Money.of("1E+2", "PEN").toString());
    assertThrows(BusinessException.class, () -> Money.of("1E-3", "PEN"));
  }
}
