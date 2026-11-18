package pe.axiz.core.crypto;

import java.util.Arrays;

/**
 * Technical storage protection. Context selects keys; binding is independently supplied row
 * identity.
 */
public interface PayloadProtector {
  byte[] protect(byte[] plaintext, String context, String binding);

  byte[] reveal(byte[] stored, String context, String binding);

  default String protect(String plaintext, String context, String binding) {
    byte[] bytes = PayloadProtection.utf8(plaintext);
    try {
      return PayloadProtection.text(protect(bytes, context, binding));
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  default String reveal(String stored, String context, String binding) {
    byte[] bytes = reveal(PayloadProtection.parse(stored), context, binding);
    try {
      return PayloadProtection.utf8(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  /**
   * Identity mode rejects the reserved protected marker instead of replaying ciphertext as data.
   */
  PayloadProtector NONE =
      new PayloadProtector() {
        @Override
        public byte[] protect(byte[] value, String context, String binding) {
          return PayloadProtection.unprotected(value, context, binding);
        }

        @Override
        public byte[] reveal(byte[] value, String context, String binding) {
          return PayloadProtection.unprotected(value, context, binding);
        }

        @Override
        public String protect(String value, String context, String binding) {
          return PayloadProtection.unprotected(value, context, binding);
        }

        @Override
        public String reveal(String value, String context, String binding) {
          return PayloadProtection.unprotected(value, context, binding);
        }
      };
}
