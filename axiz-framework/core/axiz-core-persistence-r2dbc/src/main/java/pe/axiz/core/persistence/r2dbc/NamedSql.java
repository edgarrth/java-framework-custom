package pe.axiz.core.persistence.r2dbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL with {@code :name} placeholders translated to PostgreSQL positional ones ({@code $1}). A name
 * used twice maps to the same position. Placeholders inside string literals, quoted identifiers and
 * comments are left alone, and {@code ::type} casts are not parameters.
 *
 * @param sql positional SQL
 * @param names parameter name of each position (position {@code i + 1})
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
        int end = sql.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        out.append(sql, i, end);
        i = end;
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
    int i = start + 1;
    while (i < sql.length()) {
      if (sql.charAt(i) == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2; // escaped quote
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return sql.length();
  }
}
