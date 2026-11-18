package pe.axiz.core.identifiers.contact;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import pe.axiz.core.identifiers.IdentifierMasks;
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
        // The address is split at '@' and '.', and each part is checked with a pattern without
        // nested repetition, so the check is linear in the length of the address.
        private static final Pattern LOCAL_PART = Pattern.compile("^[a-z0-9!#$%&'*+/=?^_`{|}~-]+$");
        private static final Pattern LABEL = Pattern.compile("^[a-z0-9-]{1,63}$");
        private static final Pattern TOP_LEVEL = Pattern.compile("^[a-z]{2,63}$");

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
          int at = v.indexOf('@');
          if (v.length() > 254 || at < 0) {
            return false;
          }
          for (String atom : v.substring(0, at).split("\\.", -1)) {
            if (!LOCAL_PART.matcher(atom).matches()) {
              return false;
            }
          }
          String[] labels = v.substring(at + 1).split("\\.", -1);
          if (labels.length < 2 || !TOP_LEVEL.matcher(labels[labels.length - 1]).matches()) {
            return false;
          }
          for (int i = 0; i < labels.length - 1; i++) {
            String label = labels[i];
            if (!LABEL.matcher(label).matches()
                || label.charAt(0) == '-'
                || label.charAt(label.length() - 1) == '-') {
              return false;
            }
          }
          return true;
        }

        @Override
        public String mask(String v) {
          return IdentifierMasks.email(v);
        }
      };

  @Override
  public List<IdentifierType> types() {
    return List.of(PHONE, EMAIL);
  }
}
