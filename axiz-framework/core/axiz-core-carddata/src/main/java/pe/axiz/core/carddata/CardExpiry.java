package pe.axiz.core.carddata;

import java.time.YearMonth;
import java.util.Locale;
import pe.axiz.core.errors.BusinessException;

/**
 * Vencimiento verificado contra un reloj de negocio explícito, nunca contra el reloj del sistema de
 * forma implícita.
 */
public record CardExpiry(int month, int year) {
  public CardExpiry {
    if (month < 1 || month > 12 || year < 2000 || year > 2099) throw invalid();
  }

  public static CardExpiry parse(String value) {
    if (value == null || !value.matches("[0-9]{2}/[0-9]{2}")) throw invalid();
    return new CardExpiry(
        Integer.parseInt(value.substring(0, 2)), 2000 + Integer.parseInt(value.substring(3)));
  }

  private static BusinessException invalid() {
    return new BusinessException(CardDataErrors.INVALID_EXPIRY, "Invalid card expiry");
  }

  public boolean isExpiredAt(YearMonth now) {
    return YearMonth.of(year, month).isBefore(now);
  }

  public String formatted() {
    return String.format(Locale.ROOT, "%02d/%02d", month, year - 2000);
  }
}
