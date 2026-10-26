package pe.axiz.core.sensitive;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Masks the values of sensitive keys in raw JSON text (HTTP bodies in logs).
 *
 * <p>The text is read as JSON (RFC 8259) in a single forward pass, in time linear in its length and
 * with a bounded depth ({@value #MAX_DEPTH} levels). Member names are decoded before they are
 * compared, so {@code "password"} is {@code password}. A sensitive key with a string, number or
 * boolean value is masked with its mask type; with an array or an object it is replaced whole by
 * {@value Masker#FULL_MASK}, whatever it contains. Every other key is checked at every depth.
 *
 * <p>Text that is not one complete JSON value (a body cut by a size limit, two documents in a row,
 * a syntax error or a nesting deeper than the limit) is never returned as it came: {@link #tryMask}
 * answers empty and {@link #mask} answers {@link #UNREADABLE}. A regular expression or a lenient
 * scan cannot promise that: a valid escape or a truncated string would let a secret through.
 */
public final class JsonTextMasker {

  /** Maximum nesting of arrays and objects that is read. */
  public static final int MAX_DEPTH = 64;

  /** What {@link #mask} answers for text that is not one complete JSON value. */
  public static final String UNREADABLE = "<not a complete JSON value: not logged>";

  private final ObjectMasker masker;

  public JsonTextMasker(ObjectMasker masker) {
    this.masker = masker;
  }

  /**
   * The JSON with its sensitive values masked, or {@link #UNREADABLE}.
   *
   * @param json JSON text
   * @param channel where the text goes
   * @return the masked JSON (without insignificant whitespace) or {@link #UNREADABLE}
   */
  public String mask(String json, OutputChannel channel) {
    return tryMask(json, channel).orElse(UNREADABLE);
  }

  /**
   * The JSON with its sensitive values masked, or empty when the text is not one complete JSON
   * value within {@link #MAX_DEPTH}.
   *
   * @param json JSON text
   * @param channel where the text goes
   * @return the masked JSON, without insignificant whitespace
   */
  public Optional<String> tryMask(String json, OutputChannel channel) {
    Reader reader = new Reader(json, channel);
    try {
      reader.whitespace();
      reader.value(null, 0, reader.out);
      reader.whitespace();
      return reader.at < json.length() ? Optional.empty() : Optional.of(reader.out.toString());
    } catch (Malformed e) {
      return Optional.empty();
    }
  }

  /** Thrown internally for text that is not valid JSON; never leaves this class. */
  private static final class Malformed extends Exception {
    private static final long serialVersionUID = 1L;

    Malformed() {
      super(null, null, false, false);
    }
  }

  private final class Reader {

    private final String text;
    private final OutputChannel channel;
    private final StringBuilder out;
    private int at;

    Reader(String text, OutputChannel channel) {
      this.text = text;
      this.channel = channel;
      this.out = new StringBuilder(text.length());
    }

    /**
     * Reads one value.
     *
     * @param key decoded name of the member that holds the value (null for the root and for array
     *     elements)
     * @param depth arrays and objects already open
     * @param sink where the value is written, or null to read it without writing it
     */
    void value(@Nullable String key, int depth, @Nullable StringBuilder sink) throws Malformed {
      char c = peek();
      boolean masked = key != null && masker.masksKey(key, channel);
      if (c == '{' || c == '[') {
        if (masked && sink != null) {
          composite(depth, null);
          sink.append('"').append(Masker.FULL_MASK).append('"');
        } else {
          composite(depth, sink);
        }
        return;
      }
      int start = at;
      String plain;
      if (c == '"') {
        plain = string();
      } else if (c == '-' || (c >= '0' && c <= '9')) {
        number();
        plain = text.substring(start, at);
      } else {
        plain = literal();
      }
      if (sink == null) {
        return;
      }
      String raw = text.substring(start, at);
      if (key == null || "null".equals(raw)) {
        sink.append(raw);
        return;
      }
      String result = masker.maskByKey(key, plain, channel);
      if (result == null || result.equals(plain)) {
        sink.append(raw);
      } else {
        quote(result, sink);
      }
    }

    private void composite(int depth, @Nullable StringBuilder sink) throws Malformed {
      if (depth >= MAX_DEPTH) {
        throw new Malformed();
      }
      char open = text.charAt(at++);
      char close = open == '{' ? '}' : ']';
      append(sink, open);
      whitespace();
      if (peek() == close) {
        at++;
        append(sink, close);
        return;
      }
      while (true) {
        if (open == '{') {
          if (peek() != '"') {
            throw new Malformed();
          }
          int start = at;
          String key = string();
          if (sink != null) {
            sink.append(text, start, at);
          }
          whitespace();
          expect(':');
          append(sink, ':');
          whitespace();
          value(key, depth + 1, sink);
        } else {
          value(null, depth + 1, sink);
        }
        whitespace();
        char next = peek();
        at++;
        if (next == close) {
          append(sink, close);
          return;
        }
        if (next != ',') {
          throw new Malformed();
        }
        append(sink, ',');
        whitespace();
      }
    }

    /** Reads a string starting at its opening quote and returns its decoded content. */
    private String string() throws Malformed {
      expect('"');
      StringBuilder decoded = new StringBuilder();
      while (true) {
        if (at >= text.length()) {
          throw new Malformed();
        }
        char c = text.charAt(at++);
        if (c == '"') {
          return decoded.toString();
        }
        if (c < 0x20) {
          throw new Malformed();
        }
        if (c != '\\') {
          decoded.append(c);
          continue;
        }
        if (at >= text.length()) {
          throw new Malformed();
        }
        char e = text.charAt(at++);
        switch (e) {
          case '"', '\\', '/' -> decoded.append(e);
          case 'b' -> decoded.append('\b');
          case 'f' -> decoded.append('\f');
          case 'n' -> decoded.append('\n');
          case 'r' -> decoded.append('\r');
          case 't' -> decoded.append('\t');
          case 'u' -> decoded.append(hex4());
          default -> throw new Malformed();
        }
      }
    }

    private char hex4() throws Malformed {
      if (at + 4 > text.length()) {
        throw new Malformed();
      }
      int v = 0;
      for (int k = 0; k < 4; k++) {
        int d = Character.digit(text.charAt(at++), 16);
        if (d < 0) {
          throw new Malformed();
        }
        v = v * 16 + d;
      }
      return (char) v;
    }

    /** {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}, exactly. */
    private void number() throws Malformed {
      if (peek() == '-') {
        at++;
      }
      char first = peek();
      if (first == '0') {
        at++;
      } else if (first >= '1' && first <= '9') {
        digits();
      } else {
        throw new Malformed();
      }
      if (at < text.length() && text.charAt(at) == '.') {
        at++;
        requireDigits();
      }
      if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
        at++;
        if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
          at++;
        }
        requireDigits();
      }
    }

    private void requireDigits() throws Malformed {
      if (at >= text.length() || !isDigit(text.charAt(at))) {
        throw new Malformed();
      }
      digits();
    }

    private void digits() {
      while (at < text.length() && isDigit(text.charAt(at))) {
        at++;
      }
    }

    private String literal() throws Malformed {
      for (String word : new String[] {"true", "false", "null"}) {
        if (text.startsWith(word, at)) {
          at += word.length();
          return word;
        }
      }
      throw new Malformed();
    }

    void whitespace() {
      while (at < text.length()) {
        char c = text.charAt(at);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return;
        }
        at++;
      }
    }

    private char peek() throws Malformed {
      if (at >= text.length()) {
        throw new Malformed();
      }
      return text.charAt(at);
    }

    private void expect(char c) throws Malformed {
      if (peek() != c) {
        throw new Malformed();
      }
      at++;
    }

    private void append(@Nullable StringBuilder sink, char c) {
      if (sink != null) {
        sink.append(c);
      }
    }
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /** Writes a masked value as a JSON string. */
  private static void quote(String value, StringBuilder sink) {
    sink.append('"');
    for (int k = 0; k < value.length(); k++) {
      char c = value.charAt(k);
      switch (c) {
        case '"' -> sink.append("\\\"");
        case '\\' -> sink.append("\\\\");
        case '\n' -> sink.append("\\n");
        case '\r' -> sink.append("\\r");
        case '\t' -> sink.append("\\t");
        default -> {
          if (c < 0x20) {
            sink.append(String.format("\\u%04x", (int) c));
          } else {
            sink.append(c);
          }
        }
      }
    }
    sink.append('"');
  }
}
