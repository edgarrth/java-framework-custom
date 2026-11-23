package pe.axiz.kit.r2dbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL con marcadores {@code :name} traducidos a los posicionales de PostgreSQL ({@code $1}). Un
 * nombre usado dos veces se mapea a la misma posición. Los marcadores dentro de literales de
 * cadena, identificadores entre comillas y comentarios se dejan intactos, incluidos los dollar
 * quotes de PostgreSQL, las cadenas con escape E explícitas y los comentarios de bloque anidados.
 * Los casts {@code ::type} no son parámetros. Asume el valor por defecto de PostgreSQL {@code
 * standard_conforming_strings=on}; el SQL es propiedad de la aplicación y los valores deben
 * enlazarse.
 *
 * @param sql SQL posicional
 * @param names nombre del parámetro de cada posición (posición {@code i + 1})
 */
public record NamedSql(String sql, List<String> names) {

  public NamedSql {
    Objects.requireNonNull(sql);
    names = List.copyOf(names);
  }

  public static NamedSql parse(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    List<String> names = new ArrayList<>();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"') {
        int end = closing(sql, i, c);
        out.append(sql, i, end);
        i = end;
      } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? n : end;
        out.append(sql, i, end);
        i = end;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int end = commentEnd(sql, i);
        out.append(sql, i, end);
        i = end;
      } else if (c == '$') {
        int end = dollarEnd(sql, i);
        if (end > i) {
          out.append(sql, i, end);
          i = end;
        } else {
          out.append(c);
          i++;
        }
      } else if (c == ':' && i + 1 < n && sql.charAt(i + 1) == ':') {
        out.append("::");
        i += 2;
      } else if (c == ':' && i + 1 < n && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
        int start = i + 1;
        int end = start;
        while (end < n && Character.isJavaIdentifierPart(sql.charAt(end))) {
          end++;
        }
        String name = sql.substring(start, end);
        int index = names.indexOf(name);
        if (index < 0) {
          names.add(name);
          index = names.size() - 1;
        }
        out.append('$').append(index + 1);
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return new NamedSql(out.toString(), names);
  }

  private static int closing(String sql, int start, char quote) {
    boolean escapes =
        quote == '\''
            && start > 0
            && (sql.charAt(start - 1) == 'E' || sql.charAt(start - 1) == 'e')
            && (start == 1 || !Character.isJavaIdentifierPart(sql.charAt(start - 2)));
    int i = start + 1;
    while (i < sql.length()) {
      if (escapes && sql.charAt(i) == '\\') {
        i = Math.min(i + 2, sql.length());
        continue;
      }
      if (sql.charAt(i) == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2; // comilla escapada
          continue;
        }
        if (escapes) {
          int continued = continuation(sql, i + 1);
          if (continued >= 0) {
            i = continued + 1;
            continue;
          }
        }
        return i + 1;
      }
      i++;
    }
    return sql.length();
  }

  // PostgreSQL concatena las cadenas entre comillas separadas por espacios que contienen un salto
  // de línea. Un prefijo E aplica a toda la continuación, incluso después de comentarios SQL.
  private static int continuation(String sql, int start) {
    int i = start;
    boolean newline = false;
    while (i < sql.length()) {
      char next = sql.charAt(i);
      if (Character.isWhitespace(next)) {
        newline |= next == '\n' || next == '\r';
        i++;
      } else if (sql.startsWith("--", i)) {
        int end = sql.indexOf('\n', i);
        if (end < 0) return -1;
        i = end;
      } else if (sql.startsWith("/*", i)) {
        int end = commentEnd(sql, i);
        for (int cursor = i; cursor < end; cursor++)
          newline |= sql.charAt(cursor) == '\n' || sql.charAt(cursor) == '\r';
        i = end;
      } else break;
    }
    return newline && i < sql.length() && sql.charAt(i) == '\'' ? i : -1;
  }

  private static int commentEnd(String sql, int start) {
    int depth = 1;
    int i = start + 2;
    while (i < sql.length()) {
      if (sql.startsWith("/*", i)) {
        depth++;
        i += 2;
      } else if (sql.startsWith("*/", i)) {
        depth--;
        i += 2;
        if (depth == 0) return i;
      } else i++;
    }
    return sql.length();
  }

  private static int dollarEnd(String sql, int start) {
    if (start > 0 && Character.isJavaIdentifierPart(sql.charAt(start - 1))) return start;
    int end = start + 1;
    if (end < sql.length() && sql.charAt(end) != '$') {
      if (!(sql.charAt(end) == '_' || Character.isLetter(sql.charAt(end)))) return start;
      end++;
      while (end < sql.length()
          && (sql.charAt(end) == '_' || Character.isLetterOrDigit(sql.charAt(end)))) end++;
    }
    if (end >= sql.length() || sql.charAt(end) != '$') return start;
    String delimiter = sql.substring(start, end + 1);
    int closing = sql.indexOf(delimiter, end + 1);
    return closing < 0 ? sql.length() : closing + delimiter.length();
  }
}
