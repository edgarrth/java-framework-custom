package pe.axiz.kit.r2dbc;

import java.util.Objects;

/**
 * Columna de texto almacenada cifrada (AES-256-GCM con las claves de cifrado de campos del
 * servicio). El valor que se guarda aquí es el texto plano; los conversores R2DBC del starter de
 * Spring lo cifran al escribir y lo descifran al leer. {@link #toString()} nunca lo revela.
 *
 * @param value texto plano
 */
public record EncryptedText(String value) {

  public EncryptedText {
    Objects.requireNonNull(value, "value");
  }

  public static EncryptedText of(String value) {
    return new EncryptedText(value);
  }

  @Override
  public String toString() {
    return "EncryptedText[****]";
  }
}
