package pe.axiz.core.commons.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Minimal, strict JSON parser (RFC 8259) producing {@code Map<String, Object>}, {@code List},
 * {@code String}, {@code BigDecimal}, {@code Boolean} and {@code null}. For small documents read by
 * layer 1 (token responses, secret payloads, stored envelopes); input size and depth are bounded.
 */
public final class JsonReader {

  private static final int MAX_LENGTH = 1_000_000;
  private static final int MAX_DEPTH = 64;

  private final String s;
  private int pos;

  private JsonReader(String s) {
    this.s = s;
  }

  public static @Nullable Object parse(String json) {
    if (json.length() > MAX_LENGTH) {
      throw new JsonException("JSON document too large");
    }
    JsonReader r = new JsonReader(json);
    r.ws();
    Object v = r.value(0);
    r.ws();
    if (r.pos != json.length()) {
      throw r.error("Unexpected trailing characters");
    }
    return v;
  }

  /** Parses a JSON object. */
  @SuppressWarnings("unchecked")
  public static Map<String, @Nullable Object> parseObject(String json) {
    Object v = parse(json);
    if (!(v instanceof Map)) {
      throw new JsonException("Expected a JSON object");
    }
    return (Map<String, @Nullable Object>) v;
  }

  private @Nullable Object value(int depth) {
    if (depth > MAX_DEPTH) {
      throw error("JSON nesting too deep");
    }
    if (pos >= s.length()) {
      throw error("Unexpected end of input");
    }
    char c = s.charAt(pos);
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> literal("true", true);
      case 'f' -> literal("false", false);
      case 'n' -> literal("null", null);
      default -> {
        if (c == '-' || (c >= '0' && c <= '9')) {
          yield number();
        }
        throw error("Unexpected character '" + c + "'");
      }
    };
  }

  private Map<String, @Nullable Object> object(int depth) {
    Map<String, @Nullable Object> map = new LinkedHashMap<>();
    pos++;
    ws();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      ws();
      if (peek() != '"') {
        throw error("Expected a string key");
      }
      String key = string();
      ws();
      expect(':');
      ws();
      map.put(key, value(depth + 1));
      ws();
      char c = next();
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("Expected ',' or '}'");
      }
    }
  }

  private List<@Nullable Object> array(int depth) {
    List<@Nullable Object> list = new ArrayList<>();
    pos++;
    ws();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      ws();
      list.add(value(depth + 1));
      ws();
      char c = next();
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("Expected ',' or ']'");
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= s.length()) {
        throw error("Unterminated string");
      }
      char c = s.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c < 0x20) {
        throw error("Control character in string");
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char e = next();
      switch (e) {
        case '"', '\\', '/' -> sb.append(e);
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (pos + 4 > s.length()) {
            throw error("Invalid unicode escape");
          }
          try {
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
          } catch (NumberFormatException ex) {
            throw error("Invalid unicode escape");
          }
          pos += 4;
        }
        default -> throw error("Invalid escape");
      }
    }
  }

  private BigDecimal number() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
    try {
      return new BigDecimal(s.substring(start, pos));
    } catch (NumberFormatException e) {
      throw error("Invalid number");
    }
  }

  private @Nullable Object literal(String word, @Nullable Object value) {
    if (!s.startsWith(word, pos)) {
      throw error("Invalid literal");
    }
    pos += word.length();
    return value;
  }

  private void ws() {
    while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
  }

  private char peek() {
    if (pos >= s.length()) {
      throw error("Unexpected end of input");
    }
    return s.charAt(pos);
  }

  private char next() {
    char c = peek();
    pos++;
    return c;
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("Expected '" + c + "'");
    }
  }

  private JsonException error(String message) {
    return new JsonException(message + " at position " + pos);
  }

  /** Malformed JSON. */
  public static final class JsonException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
      super(message);
    }
  }
}
