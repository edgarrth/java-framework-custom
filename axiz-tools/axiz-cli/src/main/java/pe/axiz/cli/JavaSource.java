package pe.axiz.cli;

/**
 * Enmascara los comentarios y literales Java para que las verificaciones de diagnóstico
 * inspeccionen código y no ejemplos.
 */
final class JavaSource {
  private JavaSource() {}

  static String code(String source) {
    StringBuilder result = new StringBuilder(source);
    int index = 0;
    while (index < source.length()) {
      int start = index;
      char c = source.charAt(index);
      if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
        index += 2;
        while (index < source.length() && source.charAt(index) != '\n') index++;
      } else if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
        int end = source.indexOf("*/", index + 2);
        index = end < 0 ? source.length() : end + 2;
      } else if (c == '"' || c == '\'') {
        boolean block = c == '"' && source.startsWith("\"\"\"", index);
        index += block ? 3 : 1;
        while (index < source.length()) {
          if (source.charAt(index) == '\\') {
            index = Math.min(index + 2, source.length());
          } else if (block && source.startsWith("\"\"\"", index)) {
            index += 3;
            break;
          } else if (!block && source.charAt(index) == c) {
            index++;
            break;
          } else index++;
        }
      } else {
        index++;
        continue;
      }
      for (int offset = start; offset < index; offset++) {
        if (source.charAt(offset) != '\n' && source.charAt(offset) != '\r')
          result.setCharAt(offset, ' ');
      }
    }
    return result.toString();
  }
}
