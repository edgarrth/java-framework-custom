package pe.axiz.core.fileformats;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A field of a fixed-width record. Positions are 1-based like in most legacy specifications.
 *
 * @param name field name
 * @param start first position (1-based)
 * @param length length in characters
 * @param type type
 * @param scale decimals for {@link FieldType#DECIMAL}
 * @param pattern date pattern for {@link FieldType#DATE}
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
