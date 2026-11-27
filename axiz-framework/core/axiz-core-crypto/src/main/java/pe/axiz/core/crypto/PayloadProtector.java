package pe.axiz.core.crypto;

import java.util.Arrays;

/**
 * Protección técnica del almacenamiento. El contexto selecciona las claves; el enlace es la
 * identidad de la fila suministrada de forma independiente.
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
   * El modo identidad rechaza el marcador reservado de protección en lugar de reproducir texto
   * cifrado como datos.
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
