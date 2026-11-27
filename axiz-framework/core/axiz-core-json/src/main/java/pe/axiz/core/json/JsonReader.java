package pe.axiz.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parser JSON mínimo y estricto (RFC 8259) que produce {@code Map<String, Object>}, {@code List},
 * {@code String}, {@code BigDecimal}, {@code Boolean} y {@code null}. Para documentos pequeños
 * leídos por la capa 1 (respuestas de tokens, payloads de secretos, envelopes almacenados); el
 * tamaño y la profundidad de la entrada están acotados.
 */
public final class JsonReader {

  private static final int MAX_LENGTH = 1_000_000;
  public static final int MAX_DEPTH = 64;
  private static final int MAX_NUMBER_LENGTH = 100;
  private static final int MAX_EXPONENT = 9999;

  private final String s;
  private final boolean canonicalNumbers;
  private int pos;

  private JsonReader(String s, boolean canonicalNumbers) {
    this.s = s;
    this.canonicalNumbers = canonicalNumbers;
  }

  public static @Nullable Object parse(String json) {
    return parse(json, false);
  }

  /**
   * Lee el formato numérico emitido por JsonWriter.writeCanonical sin perder la escala decimal, el
   * cero negativo ni la notación científica. No es necesario que las claves y los espacios sigan el
   * orden canónico. Se rechazan otras escrituras numéricas; el JSON ordinario debe usar parse.
   */
  public static @Nullable Object parseCanonical(String json) {
    return parse(json, true);
  }

  private static @Nullable Object parse(String json, boolean canonicalNumbers) {
    if (json.length() > MAX_LENGTH) {
      throw new JsonException("JSON document too large");
    }
    JsonReader r = new JsonReader(json, canonicalNumbers);
    r.ws();
    Object v = r.value(0);
    r.ws();
    if (r.pos != json.length()) {
      throw r.error("Unexpected trailing characters");
    }
    return v;
  }

  /** Parsea un objeto JSON. */
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
    if (depth >= MAX_DEPTH && (c == '{' || c == '[')) throw error("JSON nesting too deep");
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
      if (map.containsKey(key)) {
        throw error("Duplicate object key");
      }
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

  private Number number() {
    int start = pos;
    if (at('-')) advanceNumber(start);
    if (at('0')) {
      advanceNumber(start);
      if (digit()) throw error("Leading zero in number");
    } else {
      requireDigit();
      while (digit()) advanceNumber(start);
    }
    if (at('.')) {
      advanceNumber(start);
      requireDigit();
      while (digit()) advanceNumber(start);
    }
    if (at('e') || at('E')) {
      advanceNumber(start);
      if (at('+') || at('-')) advanceNumber(start);
      requireDigit();
      int exponent = 0;
      while (digit()) {
        exponent = exponent * 10 + s.charAt(pos) - '0';
        if (exponent > MAX_EXPONENT) throw error("Number exponent too large");
        advanceNumber(start);
      }
    }
    try {
      String lexeme = s.substring(start, pos);
      BigDecimal decimal = new BigDecimal(lexeme);
      return canonicalNumbers ? canonicalNumber(lexeme, decimal) : decimal;
    } catch (NumberFormatException e) {
      throw error("Invalid number");
    }
  }

  private Number canonicalNumber(String lexeme, BigDecimal decimal) {
    if (matchesCanonical(lexeme, decimal)) return decimal;
    BigDecimal compact = decimal.stripTrailingZeros();
    if (matchesCanonical(lexeme, compact)) return compact;
    if (lexeme.indexOf('.') < 0 && lexeme.indexOf('e') < 0 && lexeme.indexOf('E') < 0) {
      BigInteger integer = new BigInteger(lexeme);
      if (matchesCanonical(lexeme, integer)) return integer;
    }
    double floating = Double.parseDouble(lexeme);
    if (matchesCanonical(lexeme, floating)) return floating;
    throw error("Number is not in Axiz canonical format");
  }

  private static boolean matchesCanonical(String lexeme, Number candidate) {
    try {
      return lexeme.equals(JsonWriter.writeCanonical(candidate));
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  private boolean at(char c) {
    return pos < s.length() && s.charAt(pos) == c;
  }

  private boolean digit() {
    return pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9';
  }

  private void requireDigit() {
    if (!digit()) throw error("Expected number digit");
  }

  private void advanceNumber(int start) {
    if (pos - start >= (canonicalNumbers ? 130 : MAX_NUMBER_LENGTH)) throw error("number too long");
    pos++;
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

  /** JSON mal formado. */
  public static final class JsonException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
      super(message);
    }
  }
}
