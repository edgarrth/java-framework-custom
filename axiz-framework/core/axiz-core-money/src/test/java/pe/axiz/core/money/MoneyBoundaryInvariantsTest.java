package pe.axiz.core.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.errors.BusinessException;

class MoneyBoundaryInvariantsTest {
  @Test
  void signsAndComparisonsAreStrictAtZeroAndEquality() {
    for (int value : new int[] {-1, 0, 1}) {
      Money money = Money.of(BigDecimal.valueOf(value), "PEN");
      Money zero = Money.zero("PEN");
      assertEquals(value == 0, money.isZero());
      assertEquals(value > 0, money.isPositive());
      assertEquals(value < 0, money.isNegative());
      assertEquals(value > 0, money.isGreaterThan(zero));
      assertEquals(value < 0, money.isLessThan(zero));
      assertFalse(money.isGreaterThan(money));
      assertFalse(money.isLessThan(money));
      assertEquals(Money.of(BigDecimal.valueOf(Math.abs(value)), "PEN"), money.abs());
    }
    Money amount = Money.of(BigDecimal.ONE, "PEN");
    assertEquals(amount, Money.of(new BigDecimal("1.00"), Currency.getInstance("PEN")));
    assertEquals(amount.hashCode(), Money.of("1.00", "PEN").hashCode());
    assertNotEquals(amount.hashCode(), Money.of("2", "PEN").hashCode());
    assertThrows(BusinessException.class, () -> amount.minus(Money.of("1", "USD")));
    assertThrows(IllegalArgumentException.class, () -> amount.allocate(0));
    assertEquals(List.of(amount), amount.allocate(1));
  }

  @Test
  void exactRangeAndScaleBoundariesRemainUsable() {
    assertEquals(Money.zero("PEN"), Money.of(BigDecimal.ZERO.setScale(Money.MAX_SCALE), "PEN"));
    BigDecimal largest = new BigDecimal("9".repeat(Money.MAX_INTEGER_DIGITS));
    assertEquals(largest.setScale(2), Money.of(largest, "PEN").amount());
    assertThrows(BusinessException.class, () -> Money.of(largest.multiply(BigDecimal.TEN), "PEN"));
    assertThrows(
        BusinessException.class,
        () -> Money.of(BigDecimal.ZERO.setScale(Money.MAX_SCALE + 1), "PEN"));
    assertThrows(
        BusinessException.class,
        () -> Money.of(BigDecimal.ZERO.setScale(-Money.MAX_INTEGER_DIGITS - 1), "PEN"));
    assertEquals(
        Money.zero("PEN"), Money.of(BigDecimal.ZERO.setScale(-Money.MAX_INTEGER_DIGITS), "PEN"));
  }
}
