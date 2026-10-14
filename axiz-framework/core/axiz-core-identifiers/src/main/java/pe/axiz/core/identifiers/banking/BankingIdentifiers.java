package pe.axiz.core.identifiers.banking;

import java.util.List;
import java.util.Map;
import pe.axiz.core.identifiers.CheckDigits;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.IdentifierTypeProvider;

/** Banking identifiers: interbank account code (CCI), card number (PAN) and IBAN. */
public final class BankingIdentifiers implements IdentifierTypeProvider {

  /** Interbank account code: 20 digits (bank 3, branch 3, account 12, control 2). */
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
   * Interbank account code with control digits verified: the first over bank and branch, the second
   * over the account number (alternating 1-2 weights). Use it when the issuing banks of your
   * ecosystem follow that scheme.
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

  /** Payment card number: 13 to 19 digits with Luhn check. */
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
          // PCI DSS: at most first 6 and last 4 digits may be displayed
          return v.substring(0, 6) + "*".repeat(v.length() - 10) + v.substring(v.length() - 4);
        }
      };

  /** International bank account number (ISO 13616) with MOD 97-10 check. */
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

  /** Card brand detected from the issuer identification number (first digits). */
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
     * Detects the brand of a card number (only the leading digits are inspected).
     *
     * @param pan card number digits
     * @return the brand, {@link #UNKNOWN} when not recognized
     */
    public static CardBrand of(String pan) {
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

  /** Bank identification number (first 8 digits for 16+ digit cards, otherwise 6). */
  public static String bin(String pan) {
    return pan.length() >= 16 ? pan.substring(0, 8) : pan.substring(0, 6);
  }

  @Override
  public List<IdentifierType> types() {
    return List.of(CCI, CCI_STRICT, CARD, IBAN);
  }
}
