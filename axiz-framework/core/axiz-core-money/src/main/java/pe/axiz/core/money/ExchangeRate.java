package pe.axiz.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import pe.axiz.core.errors.BusinessException;

/**
 * Tipo de cambio vigente en un instante: {@code 1 from = rate to}.
 *
 * @param from moneda de origen
 * @param to moneda de destino
 * @param rate tasa positiva dentro de los límites de dígitos enteros y escala de Money
 * @param validAt instante en el que aplica
 */
public record ExchangeRate(Currency from, Currency to, BigDecimal rate, Instant validAt) {

  public ExchangeRate {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(validAt, "validAt");
    if (rate == null || rate.signum() <= 0) {
      throw new IllegalArgumentException("rate must be positive");
    }
    // Se acota la tasa antes de que la división inversa pueda expandir un exponente controlado por
    // un atacante.
    Money.requireBounded(rate);
  }

  /**
   * Convierte un monto en la moneda de origen.
   *
   * @param money monto en {@link #from()}
   * @param rounding modo de redondeo elegido por quien llama
   * @return el monto en {@link #to()}
   */
  public Money convert(Money money, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (!money.currency().equals(from)) {
      throw new BusinessException(MoneyErrors.CURRENCY_MISMATCH, "rate does not apply");
    }
    return money.multiplyTo(rate, to, rounding);
  }

  /**
   * Convierte un monto en {@link #to()} a {@link #from()} con una división y un redondeo. No se
   * crea ninguna tasa recíproca aproximada.
   */
  public Money convertInverse(Money money, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (!money.currency().equals(to)) {
      throw new BusinessException(MoneyErrors.CURRENCY_MISMATCH, "inverse rate does not apply");
    }
    return Money.of(money.amount().divide(rate, Money.scaleOf(from), rounding), from);
  }

  /**
   * Representa una tasa recíproca aproximada con la escala y el modo de redondeo elegidos por quien
   * llama. Para convertir montos sin doble redondeo se usa {@link #convertInverse(Money,
   * RoundingMode)}.
   */
  public ExchangeRate inverse(int scale, RoundingMode rounding) {
    Objects.requireNonNull(rounding, "rounding");
    if (scale < 0 || scale > Money.MAX_SCALE) {
      throw new IllegalArgumentException("inverse scale must be between 0 and " + Money.MAX_SCALE);
    }
    return new ExchangeRate(to, from, BigDecimal.ONE.divide(rate, scale, rounding), validAt);
  }
}
