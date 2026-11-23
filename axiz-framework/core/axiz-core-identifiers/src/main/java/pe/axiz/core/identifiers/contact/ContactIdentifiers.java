package pe.axiz.core.identifiers.contact;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import pe.axiz.core.identifiers.IdentifierMasks;
import pe.axiz.core.identifiers.IdentifierType;
import pe.axiz.core.identifiers.IdentifierTypeProvider;

/** Datos de contacto: números de teléfono E.164 y direcciones de correo. */
public final class ContactIdentifiers implements IdentifierTypeProvider {

  /** Número de teléfono en formato E.164, por ejemplo {@code +51987654321}. */
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

  /** Dirección de correo (subconjunto pragmático de RFC 5322, como máximo 254 caracteres). */
  public static final IdentifierType EMAIL =
      new IdentifierType() {
        // La dirección se divide en '@' y '.', y cada parte se verifica con un patrón sin
        // repetición anidada, de modo que la verificación es lineal en la longitud de la dirección.
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
