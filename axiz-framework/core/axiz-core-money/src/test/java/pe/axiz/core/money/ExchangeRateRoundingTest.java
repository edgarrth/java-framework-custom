package pe.axiz.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class ExchangeRateRoundingTest {
  private static final Currency PEN = Currency.getInstance("PEN");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void inverseFallbackRoundsTheAmountOnceEvenForLargeTransfers() {
    String[] amounts = {"100000000.00", "123456789.12", "1000000000.00", "9999999999.99"};
    String[] expected = {"26666666.67", "32921810.43", "266666666.67", "2666666666.66"};
    var rate = new ExchangeRate(USD, PEN, new BigDecimal("3.75"), NOW);
    var converter =
        new CurrencyConverter(
            (from, to, at) -> from.equals(USD) ? Optional.of(rate) : Optional.empty(), CLOCK);
    for (int i = 0; i < amounts.length; i++) {
      assertEquals(
          Money.of(expected[i], USD.getCurrencyCode()),
          converter.convert(
              Money.of(amounts[i], PEN.getCurrencyCode()), USD, RoundingMode.HALF_EVEN));
    }
    assertEquals(
        Money.of("66629354.23", USD.getCurrencyCode()),
        new ExchangeRate(USD, PEN, new BigDecimal("3.7521"), NOW)
            .convertInverse(Money.of("250000000", PEN.getCurrencyCode()), RoundingMode.HALF_EVEN));
  }

  @Test
  void ratesRejectHostileExponentsBeforeInverseArithmetic() {
    for (String text :
        List.of("1e99999999", "1e-99999999", "9".repeat(Money.MAX_INTEGER_DIGITS + 1))) {
      BigDecimal rate = new BigDecimal(text);
      assertThrows(BusinessException.class, () -> new ExchangeRate(USD, PEN, rate, NOW));
    }
    var tiny = new ExchangeRate(USD, PEN, new BigDecimal("1e-100"), NOW);
    assertThrows(
        BusinessException.class,
        () -> tiny.convertInverse(Money.of("1", "PEN"), RoundingMode.DOWN));
    assertThrows(BusinessException.class, () -> tiny.inverse(10, RoundingMode.DOWN));
  }

  @Test
  void zeroDoesNotHideAnOutOfRangeMultiplicationOperand() {
    BigDecimal excessive = new BigDecimal("9".repeat(Money.MAX_INTEGER_DIGITS + 1));
    assertThrows(
        BusinessException.class, () -> Money.zero("PEN").multiply(excessive, RoundingMode.DOWN));
  }

  @Test
  void callerChoosesRoundingForAllInexactPublicOperations() {
    for (RoundingMode mode :
        List.of(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.DOWN)) {
      String expected = mode == RoundingMode.HALF_UP ? "0.13" : "0.12";
      Money one = Money.of("1", PEN.getCurrencyCode());
      assertEquals(
          Money.of(expected, PEN.getCurrencyCode()), one.multiply(new BigDecimal("0.125"), mode));
      assertEquals(
          Money.of(expected, PEN.getCurrencyCode()), one.percentage(new BigDecimal("12.5"), mode));
      assertEquals(
          Money.of(expected, USD.getCurrencyCode()),
          new ExchangeRate(PEN, USD, new BigDecimal("0.125"), NOW).convert(one, mode));
      assertEquals(
          Money.of(expected, USD.getCurrencyCode()),
          new ExchangeRate(USD, PEN, new BigDecimal("8"), NOW).convertInverse(one, mode));
      assertEquals(
          new BigDecimal(expected),
          new ExchangeRate(USD, PEN, new BigDecimal("8"), NOW).inverse(2, mode).rate());
    }
    var rate = new ExchangeRate(USD, PEN, new BigDecimal("3"), NOW);
    assertThrows(
        ArithmeticException.class,
        () -> rate.convertInverse(Money.of("1", PEN.getCurrencyCode()), RoundingMode.UNNECESSARY));
    assertThrows(ArithmeticException.class, () -> rate.inverse(10, RoundingMode.UNNECESSARY));
    assertThrows(
        ArithmeticException.class,
        () ->
            Money.of("1", PEN.getCurrencyCode())
                .percentage(new BigDecimal("12.5"), RoundingMode.UNNECESSARY));
    assertThrows(
        ArithmeticException.class,
        () ->
            Money.of("1", PEN.getCurrencyCode())
                .multiply(new BigDecimal("0.125"), RoundingMode.UNNECESSARY));
    assertThrows(
        ArithmeticException.class,
        () ->
            new ExchangeRate(PEN, USD, new BigDecimal("0.125"), NOW)
                .convert(Money.of("1", PEN.getCurrencyCode()), RoundingMode.UNNECESSARY));
    assertEquals(
        Money.of("1", USD.getCurrencyCode()),
        rate.convertInverse(Money.of("3", PEN.getCurrencyCode()), RoundingMode.UNNECESSARY));
  }

  @Test
  void directQuotesTakePrecedenceAndInvalidContractsAreRejected() throws Exception {
    var direct = new ExchangeRate(PEN, USD, new BigDecimal("0.27"), NOW);
    var converter =
        new CurrencyConverter(
            (from, to, at) -> {
              assertEquals(PEN, from, "inverse quote must not be requested when direct exists");
              assertEquals(NOW, at);
              return Optional.of(direct);
            },
            CLOCK);
    assertEquals(
        Money.of("27", USD.getCurrencyCode()),
        converter.convert(Money.of("100", PEN.getCurrencyCode()), USD, RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () -> direct.convert(Money.of("1", USD.getCurrencyCode()), RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () -> direct.convertInverse(Money.of("1", PEN.getCurrencyCode()), RoundingMode.DOWN));
    assertThrows(
        NullPointerException.class,
        () -> direct.convert(Money.of("1", PEN.getCurrencyCode()), null));
    assertThrows(
        NullPointerException.class,
        () -> direct.convertInverse(Money.of("1", USD.getCurrencyCode()), null));
    assertThrows(NullPointerException.class, () -> direct.inverse(10, null));
    assertThrows(IllegalArgumentException.class, () -> direct.inverse(-1, RoundingMode.DOWN));
    assertThrows(
        IllegalArgumentException.class,
        () -> direct.inverse(Money.MAX_SCALE + 1, RoundingMode.DOWN));
    assertEquals(0, direct.inverse(0, RoundingMode.DOWN).rate().scale());
    assertEquals(
        Money.MAX_SCALE, direct.inverse(Money.MAX_SCALE, RoundingMode.DOWN).rate().scale());
    var missing = new CurrencyConverter((from, to, at) -> Optional.empty(), CLOCK);
    Money one = Money.of("1", PEN.getCurrencyCode());
    assertSame(one, missing.convert(one, PEN, RoundingMode.UNNECESSARY));
    assertThrows(NullPointerException.class, () -> missing.convert(one, PEN, null));
    assertThrows(BusinessException.class, () -> missing.convert(one, USD, RoundingMode.DOWN));
    assertThrows(
        NoSuchMethodException.class, () -> Money.class.getMethod("multiply", BigDecimal.class));
    assertThrows(
        NoSuchMethodException.class, () -> Money.class.getMethod("percentage", BigDecimal.class));
    assertThrows(
        NoSuchMethodException.class, () -> ExchangeRate.class.getMethod("convert", Money.class));
    assertThrows(NoSuchMethodException.class, () -> ExchangeRate.class.getMethod("inverse"));
    assertThrows(
        NoSuchMethodException.class,
        () -> CurrencyConverter.class.getMethod("convert", Money.class, Currency.class));
  }

  @Test
  void conversionsAgreeWithIntegerRationalOracleAcrossScalesSignsAndMoneyBounds() {
    Random random = new Random(20261113L);
    Currency[] currencies = {USD, PEN, Currency.getInstance("JPY"), Currency.getInstance("BHD")};
    for (int run = 0; run < 900; run++) {
      Currency source = currencies[run % currencies.length];
      Currency target = currencies[(run + 1) % currencies.length];
      int sourceScale = source.getDefaultFractionDigits();
      int targetScale = target.getDefaultFractionDigits();
      int digits = 1 + random.nextInt(Money.MAX_INTEGER_DIGITS + sourceScale);
      BigInteger minor = new BigInteger(digits * 4, random).mod(BigInteger.TEN.pow(digits));
      if (random.nextBoolean()) minor = minor.negate();
      Money amount = Money.of(new BigDecimal(minor, sourceScale), source);
      BigDecimal quote =
          new BigDecimal(BigInteger.valueOf(1 + random.nextInt(999999)), random.nextInt(9) - 2);
      for (boolean inverse : new boolean[] {false, true}) {
        ExchangeRate rate =
            inverse
                ? new ExchangeRate(target, source, quote, NOW)
                : new ExchangeRate(source, target, quote, NOW);
        BigInteger numerator = inverse ? minor : minor.multiply(quote.unscaledValue());
        BigInteger denominator = inverse ? quote.unscaledValue() : BigInteger.ONE;
        int power = targetScale - sourceScale + (inverse ? quote.scale() : -quote.scale());
        if (power >= 0) numerator = numerator.multiply(BigInteger.TEN.pow(power));
        else denominator = denominator.multiply(BigInteger.TEN.pow(-power));
        for (RoundingMode mode :
            List.of(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.DOWN)) {
          BigInteger rounded = roundRational(numerator, denominator, mode);
          if (rounded.abs().compareTo(BigInteger.TEN.pow(Money.MAX_INTEGER_DIGITS + targetScale))
              >= 0) {
            assertThrows(BusinessException.class, () -> convert(rate, amount, mode, inverse));
          } else {
            assertEquals(
                new BigDecimal(rounded, targetScale),
                convert(rate, amount, mode, inverse).amount());
          }
        }
      }
    }
  }

  private static Money convert(
      ExchangeRate rate, Money amount, RoundingMode mode, boolean inverse) {
    return inverse ? rate.convertInverse(amount, mode) : rate.convert(amount, mode);
  }

  private static BigInteger roundRational(
      BigInteger numerator, BigInteger denominator, RoundingMode mode) {
    BigInteger[] parts = numerator.abs().divideAndRemainder(denominator);
    int half = parts[1].shiftLeft(1).compareTo(denominator);
    boolean increment =
        mode != RoundingMode.DOWN
            && (half > 0 || (half == 0 && (mode == RoundingMode.HALF_UP || parts[0].testBit(0))));
    BigInteger magnitude = increment ? parts[0].add(BigInteger.ONE) : parts[0];
    return numerator.signum() < 0 ? magnitude.negate() : magnitude;
  }
}
