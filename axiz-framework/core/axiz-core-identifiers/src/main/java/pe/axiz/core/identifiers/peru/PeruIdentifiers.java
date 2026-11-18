package pe.axiz.core.identifiers.peru;

import java.util.List;
import java.util.regex.Pattern;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.IdentifierTypeProvider;

/** Peruvian national documents: DNI, RUC and foreigner card (CE). */
public final class PeruIdentifiers implements IdentifierTypeProvider {

  /** National identity document: 8 digits. */
  public static final IdentifierType DNI =
      new IdentifierType() {
        @Override
        public String name() {
          return "dni";
        }

        @Override
        public boolean isValid(String v) {
          return v.length() == 8 && CheckDigits.allDigits(v);
        }
      };

  /** Taxpayer number: 11 digits, valid prefix and modulo-11 check digit. */
  public static final IdentifierType RUC =
      new IdentifierType() {
        private static final int[] WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

        @Override
        public String name() {
          return "ruc";
        }

        @Override
        public boolean isValid(String v) {
          if (v.length() != 11 || !CheckDigits.allDigits(v)) {
            return false;
          }
          String prefix = v.substring(0, 2);
          if (!List.of("10", "15", "16", "17", "20").contains(prefix)) {
            return false;
          }
          return CheckDigits.mod11(v.substring(0, 10), WEIGHTS) == v.charAt(10) - '0';
        }
      };

  /** Foreigner card: 9 to 12 alphanumeric characters. */
  public static final IdentifierType CE =
      new IdentifierType() {
        private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{9,12}$");

        @Override
        public String name() {
          return "ce";
        }

        @Override
        public boolean isValid(String v) {
          return FORMAT.matcher(v).matches();
        }
      };

  @Override
  public List<IdentifierType> types() {
    return List.of(DNI, RUC, CE);
  }
}
