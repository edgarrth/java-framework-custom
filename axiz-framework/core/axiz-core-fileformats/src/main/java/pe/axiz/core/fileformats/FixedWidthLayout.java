package pe.axiz.core.fileformats;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Layout of a fixed-width record: parses lines into typed values and formats values back.
 * Overlapping fields are rejected when the layout is built.
 */
public final class FixedWidthLayout {

  private final String name;
  private final List<FixedField> fields;
  private final int recordLength;

  public FixedWidthLayout(String name, List<FixedField> fields) {
    this.name = Objects.requireNonNull(name);
    List<FixedField> sorted = new ArrayList<>(fields);
    sorted.sort(Comparator.comparingInt(FixedField::start));
    int lastEnd = 0;
    for (FixedField f : sorted) {
      if (f.start() <= lastEnd) {
        throw new IllegalArgumentException("Field " + f.name() + " overlaps in layout " + name);
      }
      lastEnd = f.end();
    }
    this.fields = List.copyOf(sorted);
    this.recordLength = lastEnd;
  }

  public String name() {
    return name;
  }

  public int recordLength() {
    return recordLength;
  }

  public Map<String, @Nullable Object> parse(String line, int lineNumber) {
    if (line.length() < recordLength) {
      throw new FileFormatException("Record " + name + " shorter than " + recordLength, lineNumber);
    }
    Map<String, @Nullable Object> values = new LinkedHashMap<>();
    for (FixedField f : fields) {
      String raw = line.substring(f.start() - 1, f.end());
      values.put(f.name(), parseValue(f, raw, lineNumber));
    }
    return values;
  }

  private static @Nullable Object parseValue(FixedField f, String raw, int line) {
    try {
      return switch (f.type()) {
        case TEXT -> raw.stripTrailing();
        case INTEGER -> raw.isBlank() ? null : new BigInteger(raw.strip());
        case DECIMAL ->
            raw.isBlank() ? null : new BigDecimal(new BigInteger(raw.strip()), f.scale());
        case DATE ->
            raw.isBlank() || raw.chars().allMatch(c -> c == '0')
                ? null
                : LocalDate.parse(
                    raw, DateTimeFormatter.ofPattern(Objects.requireNonNull(f.pattern())));
      };
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new FileFormatException("Invalid value for field " + f.name(), line);
    }
  }

  public String format(Map<String, ?> values) {
    char[] out = new char[recordLength];
    java.util.Arrays.fill(out, ' ');
    for (FixedField f : fields) {
      String text = formatValue(f, values.get(f.name()));
      text.getChars(0, text.length(), out, f.start() - 1);
    }
    return new String(out);
  }

  private static String formatValue(FixedField f, @Nullable Object value) {
    String text =
        switch (f.type()) {
          case TEXT -> value == null ? "" : value.toString();
          case INTEGER -> value == null ? "0" : new BigInteger(value.toString()).toString();
          case DECIMAL ->
              value == null
                  ? "0"
                  : new BigDecimal(value.toString())
                      .setScale(f.scale(), java.math.RoundingMode.UNNECESSARY)
                      .unscaledValue()
                      .toString();
          case DATE ->
              value == null
                  ? "0".repeat(f.length())
                  : DateTimeFormatter.ofPattern(Objects.requireNonNull(f.pattern()))
                      .format((LocalDate) value);
        };
    if (text.startsWith("-")) {
      throw new IllegalArgumentException("Negative values are not supported in field " + f.name());
    }
    if (text.length() > f.length()) {
      throw new IllegalArgumentException("Value too long for field " + f.name());
    }
    return f.type() == FieldType.TEXT
        ? text + " ".repeat(f.length() - text.length())
        : "0".repeat(f.length() - text.length()) + text;
  }
}
