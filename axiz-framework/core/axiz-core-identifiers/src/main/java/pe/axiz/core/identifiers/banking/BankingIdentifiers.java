package pe.axiz.core.identifiers.banking;

import java.util.List;
import java.util.Map;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.identifiers.IdentifierMasks;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.IdentifierTypeProvider;

/**
 * Identificadores bancarios: código de cuenta interbancario (CCI), número de tarjeta (PAN) e IBAN.
 */
public final class BankingIdentifiers implements IdentifierTypeProvider {

  /** Código de cuenta interbancario: 20 dígitos (banco 3, agencia 3, cuenta 12, control 2). */
  public static final IdentifierType CCI =
      new IdentifierType() {
        @Override
        public String name() {
          return "cci";
        }

        @Override
        public boolean isValid(String v) {
          return v.length() == 20 && CheckDigits.allDigits(v);
        }
      };

  /**
   * Código de cuenta interbancario con dígitos de control verificados: el primero sobre banco y
   * agencia, el segundo sobre el número de cuenta (pesos alternos 1-2). Se usa cuando los bancos
   * emisores del ecosistema siguen ese esquema.
   */
  public static final IdentifierType CCI_STRICT =
      new IdentifierType() {
        @Override
        public String name() {
          return "cci-strict";
        }

        @Override
        public boolean isValid(String v) {
          if (!CCI.isValid(v)) {
            return false;
          }
          int first = CheckDigits.alternatingWeights(v.substring(0, 6));
          int second = CheckDigits.alternatingWeights(v.substring(6, 18));
          return first == v.charAt(18) - '0' && second == v.charAt(19) - '0';
        }
      };

  /** Número de tarjeta de pago: 13 a 19 dígitos con verificación Luhn. */
  public static final IdentifierType CARD =
      new IdentifierType() {
        @Override
        public String name() {
          return "card";
        }

        @Override
        public boolean isValid(String v) {
          return v.length() >= 13 && v.length() <= 19 && CheckDigits.luhn(v);
        }

        @Override
        public String mask(String v) {
          return IdentifierMasks.card(v);
        }
      };

  /** Número de cuenta bancaria internacional (ISO 13616) con verificación MOD 97-10. */
  public static final IdentifierType IBAN =
      new IdentifierType() {
        @Override
        public String name() {
          return "iban";
        }

        @Override
        public boolean isValid(String v) {
          if (v.length() < 15 || v.length() > 34 || !v.matches("^[A-Z]{2}[0-9]{2}[A-Z0-9]+$")) {
            return false;
          }
          Integer expected = IBAN_LENGTHS.get(v.substring(0, 2));
          if (expected != null && expected != v.length()) {
            return false;
          }
          String rearranged = v.substring(4) + v.substring(0, 4);
          StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
          for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            numeric.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
          }
          return CheckDigits.mod97(numeric.toString()) == 1;
        }
      };

  private static final Map<String, Integer> IBAN_LENGTHS =
      Map.ofEntries(
          Map.entry("AD", 24),
          Map.entry("AT", 20),
          Map.entry("BE", 16),
          Map.entry("BR", 29),
          Map.entry("CH", 21),
          Map.entry("CR", 22),
          Map.entry("DE", 22),
          Map.entry("DK", 18),
          Map.entry("DO", 28),
          Map.entry("ES", 24),
          Map.entry("FI", 18),
          Map.entry("FR", 27),
          Map.entry("GB", 22),
          Map.entry("GT", 28),
          Map.entry("IE", 22),
          Map.entry("IT", 27),
          Map.entry("LU", 20),
          Map.entry("NL", 18),
          Map.entry("NO", 15),
          Map.entry("PL", 28),
          Map.entry("PT", 25),
          Map.entry("SE", 24),
          Map.entry("SV", 28));

  /**
   * Marcas de tarjeta. A cuál pertenece una tarjeta lo decide un {@link CardSchemeResolver}: en
   * producción, una {@link IinRangeTable} cargada desde la tabla IIN/BIN gobernada, nunca desde
   * rangos escritos en código.
   */
  public enum CardBrand {
    VISA,
    MASTERCARD,
    AMEX,
    DINERS,
    DISCOVER,
    JCB,
    UNIONPAY,
    UNKNOWN;

    /**
     * Estimación de mejor esfuerzo a partir de unos pocos dígitos iniciales conocidos, solo para
     * presentación y telemetría.
     *
     * <p><strong>No sirve para decisiones de enrutamiento, autorización, aceptación, precios ni
     * fraude</strong>: los rangos IIN cambian, se superponen, incluyen rangos co-branded y privados
     * y los publican las marcas. Esas decisiones usan un {@link CardSchemeResolver} respaldado por
     * la tabla gobernada ({@link IinRangeTable}).
     *
     * @param pan dígitos del número de tarjeta
     * @return la marca estimada, {@link #UNKNOWN} cuando no se reconoce
     */
    public static CardBrand guess(String pan) {
      if (pan.length() < 6 || !CheckDigits.allDigits(pan.substring(0, 6))) {
        return UNKNOWN;
      }
      int two = Integer.parseInt(pan.substring(0, 2));
      int three = Integer.parseInt(pan.substring(0, 3));
      int four = Integer.parseInt(pan.substring(0, 4));
      if (pan.charAt(0) == '4') {
        return VISA;
      }
      if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
        return MASTERCARD;
      }
      if (two == 34 || two == 37) {
        return AMEX;
      }
      if (two == 36 || two == 38 || (three >= 300 && three <= 305)) {
        return DINERS;
      }
      if (four == 6011 || two == 65 || (three >= 644 && three <= 649)) {
        return DISCOVER;
      }
      if (four >= 3528 && four <= 3589) {
        return JCB;
      }
      if (two == 62) {
        return UNIONPAY;
      }
      return UNKNOWN;
    }
  }

  /**
   * Número de identificación bancaria (los primeros 8 dígitos para tarjetas de 16 o más dígitos; en
   * otro caso, 6).
   */
  public static String bin(String pan) {
    return pan.length() >= 16 ? pan.substring(0, 8) : pan.substring(0, 6);
  }

  @Override
  public List<IdentifierType> types() {
    return List.of(CCI, CCI_STRICT, CARD, IBAN);
  }
}
