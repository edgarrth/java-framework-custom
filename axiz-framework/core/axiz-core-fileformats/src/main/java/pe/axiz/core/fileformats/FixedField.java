package pe.axiz.core.fileformats;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Campo de un registro de ancho fijo. Las posiciones empiezan en 1, como en la mayoría de las
 * especificaciones de mainframe.
 *
 * @param name nombre del campo
 * @param start primera posición (desde 1)
 * @param length longitud en caracteres
 * @param type tipo
 * @param scale decimales para {@link FieldType#DECIMAL}
 * @param pattern patrón de fecha para {@link FieldType#DATE}
 */
public record FixedField(
    String name, int start, int length, FieldType type, int scale, @Nullable String pattern) {

  public FixedField {
    Objects.requireNonNull(name);
    Objects.requireNonNull(type);
    if (start < 1 || length < 1) {
      throw new IllegalArgumentException("Invalid position for field " + name);
    }
    if (type == FieldType.DATE && pattern == null) {
      throw new IllegalArgumentException("Date field " + name + " needs a pattern");
    }
  }

  public static FixedField text(String name, int start, int length) {
    return new FixedField(name, start, length, FieldType.TEXT, 0, null);
  }

  public static FixedField integer(String name, int start, int length) {
    return new FixedField(name, start, length, FieldType.INTEGER, 0, null);
  }

  public static FixedField decimal(String name, int start, int length, int scale) {
    return new FixedField(name, start, length, FieldType.DECIMAL, scale, null);
  }

  public static FixedField date(String name, int start, int length, String pattern) {
    return new FixedField(name, start, length, FieldType.DATE, 0, pattern);
  }

  public int end() {
    return start + length - 1;
  }
}
