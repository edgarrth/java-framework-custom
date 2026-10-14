package pe.axiz.core.identifiers.contact;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.IdentifierTypeProvider;

/** Contact data: E.164 phone numbers and email addresses. */
public final class ContactIdentifiers implements IdentifierTypeProvider {

  /** Phone number in E.164 format, e.g. {@code +51987654321}. */
  public static final IdentifierType PHONE =
      new IdentifierType() {
        private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

        @Override
        public String name() {
          return "phone";
        }

        @Override
        public String normalize(String raw) {
          return raw.replaceAll("[\\s().-]", "");
        }

        @Override
        public boolean isValid(String v) {
          return E164.matcher(v).matches();
        }
      };

  /** Email address (pragmatic RFC 5322 subset, at most 254 characters). */
  public static final IdentifierType EMAIL =
      new IdentifierType() {
        private static final Pattern FORMAT =
            Pattern.compile(
                "^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                    + "@([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

        @Override
        public String name() {
          return "email";
        }

        @Override
        public String normalize(String raw) {
          return raw.strip().toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean isValid(String v) {
          return v.length() <= 254 && FORMAT.matcher(v).matches();
        }

        @Override
        public String mask(String v) {
          int at = v.indexOf('@');
          if (at <= 1) {
            return "*" + v.substring(Math.max(at, 0));
          }
          return v.charAt(0) + "*".repeat(at - 1) + v.substring(at);
        }
      };

  @Override
  public List<IdentifierType> types() {
    return List.of(PHONE, EMAIL);
  }
}
