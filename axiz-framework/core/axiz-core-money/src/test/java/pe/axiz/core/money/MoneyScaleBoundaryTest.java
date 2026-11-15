package pe.axiz.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class MoneyScaleBoundaryTest {
  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency PEN = Currency.getInstance("PEN");
  private static final Currency CLF = Currency.getInstance("CLF");
  private static final BigDecimal HIGH_SCALE = new BigDecimal("3." + "7".repeat(100));

  @Test
  void acceptedHighScaleOperandsAreRoundedOnlyOnce() {
    var rate = new ExchangeRate(USD, PEN, HIGH_SCALE, Instant.EPOCH);
    assertEquals(
        Money.of("3.78", PEN.getCurrencyCode()),
        rate.convert(Money.of("1", USD.getCurrencyCode()), RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("0.26", USD.getCurrencyCode()),
        rate.convertInverse(Money.of("1", PEN.getCurrencyCode()), RoundingMode.HALF_EVEN));
    var inverse =
        new ExchangeRate(USD, PEN, new BigDecimal("3.75"), Instant.EPOCH)
            .inverse(Money.MAX_SCALE, RoundingMode.HALF_EVEN);
    assertEquals(
        Money.of("0.27", USD.getCurrencyCode()),
        inverse.convert(Money.of("1", PEN.getCurrencyCode()), RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("3.78", USD.getCurrencyCode()),
        Money.of("1", USD.getCurrencyCode()).multiply(HIGH_SCALE, RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("0.04", USD.getCurrencyCode()),
        Money.of("1", USD.getCurrencyCode()).percentage(HIGH_SCALE, RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("-3.78", USD.getCurrencyCode()),
        Money.of("-1", USD.getCurrencyCode()).multiply(HIGH_SCALE, RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("-3.78", USD.getCurrencyCode()),
        Money.of("1", USD.getCurrencyCode()).multiply(HIGH_SCALE.negate(), RoundingMode.HALF_EVEN));
    assertEquals(Money.zero(USD), Money.zero(USD).percentage(HIGH_SCALE, RoundingMode.UNNECESSARY));
    assertThrows(
        ArithmeticException.class,
        () -> Money.of("1", USD.getCurrencyCode()).multiply(HIGH_SCALE, RoundingMode.UNNECESSARY));
  }

  @Test
  void percentageReachesTheExactDerivedProductScale() {
    BigDecimal percent = new BigDecimal("1." + "0".repeat(99) + "1");
    Money amount = Money.of("1.0000", CLF.getCurrencyCode());
    assertEquals(
        106, amount.amount().multiply(percent.movePointLeft(Money.PERCENTAGE_SHIFT)).scale());
    assertEquals(106, Money.MAX_PRODUCT_SCALE);
    assertEquals(
        Money.of("0.0100", CLF.getCurrencyCode()),
        amount.percentage(percent, RoundingMode.HALF_EVEN));
    assertEquals(
        Money.of("0.0100", CLF.getCurrencyCode()),
        Money.roundedProduct(new BigDecimal("0.01").setScale(106), CLF, RoundingMode.UNNECESSARY));
    assertThrows(
        BusinessException.class,
        () -> Money.roundedProduct(BigDecimal.ZERO.setScale(107), CLF, RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () -> Money.roundedProduct(BigDecimal.ZERO.setScale(-31), CLF, RoundingMode.DOWN));
    assertEquals(
        Money.zero(CLF),
        Money.roundedProduct(BigDecimal.ZERO.setScale(-30), CLF, RoundingMode.DOWN));
  }

  @Test
  void externalBoundsRemainStrictEvenForZeroAndDerivedOperations() {
    for (BigDecimal invalid :
        List.of(
            BigDecimal.ZERO.setScale(101),
            BigDecimal.ONE.scaleByPowerOfTen(-101),
            new BigDecimal("1e99999999"),
            new BigDecimal("1e-99999999"))) {
      if (invalid.signum() == 0) {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ExchangeRate(USD, PEN, invalid, Instant.EPOCH));
      } else {
        assertThrows(
            BusinessException.class, () -> new ExchangeRate(USD, PEN, invalid, Instant.EPOCH));
      }
      assertThrows(
          BusinessException.class, () -> Money.zero(USD).multiply(invalid, RoundingMode.DOWN));
      assertThrows(
          BusinessException.class, () -> Money.zero(USD).percentage(invalid, RoundingMode.DOWN));
      assertThrows(BusinessException.class, () -> Money.rounded(invalid, USD, RoundingMode.DOWN));
      assertThrows(BusinessException.class, () -> Money.of(invalid, USD.getCurrencyCode()));
    }
    assertThrows(BusinessException.class, () -> Money.parse("USD 1e-101"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExchangeRate(USD, PEN, BigDecimal.ONE, Instant.EPOCH)
                .inverse(101, RoundingMode.DOWN));
  }

  @Test
  void finalOverflowAndOperandBoundsRemainEnforced() {
    Money large = Money.of("9".repeat(Money.MAX_INTEGER_DIGITS), USD.getCurrencyCode());
    assertThrows(BusinessException.class, () -> large.multiply(HIGH_SCALE, RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () -> large.percentage(HIGH_SCALE.movePointRight(2), RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () ->
            new ExchangeRate(USD, PEN, HIGH_SCALE, Instant.EPOCH)
                .convert(large, RoundingMode.DOWN));
    assertThrows(
        BusinessException.class,
        () -> Money.zero(USD).multiply(new BigDecimal("1e30"), RoundingMode.DOWN));
    assertEquals(
        Money.of("0.01", USD.getCurrencyCode()),
        Money.of("1", USD.getCurrencyCode()).percentage(BigDecimal.ONE, RoundingMode.UNNECESSARY));
    assertEquals(
        Money.of("1", USD.getCurrencyCode()),
        Money.of("1", USD.getCurrencyCode())
            .multiply(BigDecimal.ONE.setScale(100), RoundingMode.UNNECESSARY));
  }

  @Test
  void everySupportedCurrencyHasAnExplicitlyValidatedScale() {
    for (Currency currency : Currency.getAvailableCurrencies()) {
      int scale = Money.scaleOf(currency);
      assertTrue(scale >= 0 && scale <= Money.MAX_CURRENCY_SCALE, currency.getCurrencyCode());
      assertEquals(scale, Money.ofMinor(1, currency.getCurrencyCode()).amount().scale());
      assertEquals(scale, Money.parse(currency.getCurrencyCode() + " 0").amount().scale());
    }
    assertEquals(2, Money.checkedCurrencyScale(-1));
    for (int scale = 0; scale <= 4; scale++) assertEquals(scale, Money.checkedCurrencyScale(scale));
    assertThrows(BusinessException.class, () -> Money.checkedCurrencyScale(-2));
    assertThrows(BusinessException.class, () -> Money.checkedCurrencyScale(5));
  }
}
