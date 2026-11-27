package pe.axiz.core.cache;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Construye claves de caché a partir de valores, sin ambigüedad: cada parte lleva su tipo, {@code
 * null} tiene su propio marcador y los separadores dentro de los valores se escapan. Así, {@code
 * "7"} y {@code 7}, {@code null} y {@code "_"}, o {@code ("a:b", "c")} y {@code ("a", "b:c")} nunca
 * comparten una clave.
 *
 * <p>Valores soportados: cadenas y caracteres, números, booleanos, enums, {@link UUID}, valores de
 * {@code java.time}, {@link Currency}, {@link Locale}, {@code byte[]}, arrays, listas, conjuntos y
 * mapas (conjuntos y mapas en un orden estable), {@link Optional}, records (por sus componentes) y
 * cualquier tipo que implemente {@link CacheKeyPart}. Todo lo demás se rechaza: el {@code
 * toString()} de un objeto arbitrario no es una identidad estable (puede cambiar, incluir
 * direcciones de memoria u omitir campos), así que mezclaría entradas en silencio o nunca
 * acertaría.
 *
 * <p>El formato se versiona con {@link #FORMAT}: un cambio de formato cambia todas las claves, de
 * modo que las entradas con otro formato simplemente no se vuelven a leer y expiran con su TTL.
 */
public final class CacheKeys {

  /**
   * Versión del formato de clave; forma parte de cada clave de método que construye el framework.
   */
  public static final String FORMAT = "k5";

  private static final String NULL = "-";

  private CacheKeys() {}

  /** Clave de las partes indicadas, unidas con {@code :}. */
  public static String of(@Nullable Object... parts) {
    if (parts == null) {
      return NULL;
    }
    StringJoiner j = new StringJoiner(":");
    for (Object p : parts) {
      j.add(part(p));
    }
    return j.toString();
  }

  /**
   * Codifica un valor como {@code <type>=<value>} ({@code -} para {@code null}).
   *
   * @throws IllegalArgumentException para un tipo sin clave estable (ver el comentario de la clase)
   */
  public static String part(@Nullable Object value) {
    if (value == null) {
      return NULL;
    }
    return switch (value) {
      case CacheKeyPart p -> "k(" + p.getClass().getName() + ")=" + escape(p.cacheKey(), false);
      case String s -> "s=" + escape(s, false);
      case CharSequence s -> "s=" + escape(s.toString(), false);
      case Character c -> "c=" + escape(c.toString(), false);
      case Boolean b -> "b=" + b;
      case Integer i -> "i=" + i;
      case Long l -> "l=" + l;
      case Short s -> "h=" + s;
      case Byte b -> "y=" + b;
      // BigDecimal conserva su escala: 1.0 y 1.00 también son valores distintos para equals()
      case BigDecimal d -> "n=" + d;
      case BigInteger i -> "N=" + i;
      case Double d -> "d=" + d;
      case Float f -> "f=" + f;
      case Enum<?> e -> "e(" + e.getDeclaringClass().getName() + ")=" + e.name();
      case UUID u -> "u=" + u;
      case Currency c -> "C=" + c.getCurrencyCode();
      case Locale l -> "L=" + l.toLanguageTag();
      case TemporalAccessor t -> "t(" + t.getClass().getName() + ")=" + escape(t.toString(), false);
      case byte[] bytes -> "B=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      case Optional<?> o -> "o=[" + (o.isPresent() ? nested(o.get()) : "") + "]";
      case List<?> list -> "[" + join(list) + "]";
      case Set<?> set -> "{" + join(sorted(set)) + "}";
      case Map<?, ?> map -> "m{" + entries(map) + "}";
      // cualquier otra colección conserva su clase: un ArrayDeque nunca es igual a una List
      case Collection<?> c -> "q(" + c.getClass().getName() + ")=[" + join(c) + "]";
      case Record r -> "r(" + r.getClass().getName() + ")=[" + components(r) + "]";
      default -> {
        if (value.getClass().isArray()) {
          List<Object> items = new ArrayList<>();
          for (int i = 0; i < Array.getLength(value); i++) {
            items.add(Array.get(value, i));
          }
          // la clase del array conserva el tipo de componente: int[]{7}, Integer[]{7}, long[]{7} e
          // int[][]{{7}} son argumentos distintos y nunca comparten una entrada
          yield "a(" + value.getClass().getName() + ")=[" + join(items) + "]";
        }
        throw new IllegalArgumentException(
            "No stable cache key for "
                + value.getClass().getName()
                + ": pass a record, a supported value or implement CacheKeyPart");
      }
    };
  }

  /** Parte dentro de un contenedor: sus propios separadores se escapan una vez más. */
  private static String nested(@Nullable Object value) {
    return escape(part(value), true);
  }

  private static String join(Collection<?> values) {
    StringJoiner j = new StringJoiner(",");
    for (Object v : values) {
      j.add(nested(v));
    }
    return j.toString();
  }

  private static List<String> sorted(Set<?> set) {
    List<String> out = new ArrayList<>();
    for (Object v : set) {
      out.add(part(v));
    }
    out.sort(null);
    return out;
  }

  private static String entries(Map<?, ?> map) {
    List<String> out = new ArrayList<>();
    map.forEach((k, v) -> out.add(nested(k) + "=" + nested(v)));
    out.sort(null);
    return String.join(",", out);
  }

  private static String components(Record r) {
    StringJoiner j = new StringJoiner(",");
    for (RecordComponent c : r.getClass().getRecordComponents()) {
      try {
        c.getAccessor().setAccessible(true);
        j.add(nested(c.getAccessor().invoke(r)));
      } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
        throw new IllegalArgumentException(
            "No stable cache key for " + r.getClass().getName() + "." + c.getName(), e);
      }
    }
    return j.toString();
  }

  /**
   * Escapa el separador de clave ({@code :}) y el carácter de escape; dentro de contenedores
   * también la sintaxis del contenedor, de modo que el contenido de los elementos no puede
   * falsificar sus límites.
   */
  private static String escape(String s, boolean nested) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      boolean special =
          ch == '\\'
              || ch == ':'
              || (nested && (ch == ',' || ch == '=' || "[]{}".indexOf(ch) >= 0));
      if (special) {
        b.append('\\');
      }
      b.append(ch);
    }
    return b.toString();
  }
}
