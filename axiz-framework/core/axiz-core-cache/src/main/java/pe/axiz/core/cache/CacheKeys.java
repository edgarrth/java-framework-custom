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
 * Builds cache keys from values, without ambiguity: every part carries its type, {@code null} has
 * its own marker and separators inside values are escaped. So {@code "7"} and {@code 7}, {@code
 * null} and {@code "_"}, or {@code ("a:b", "c")} and {@code ("a", "b:c")} never share a key.
 *
 * <p>Supported values: strings and characters, numbers, booleans, enums, {@link UUID}, {@code
 * java.time} values, {@link Currency}, {@link Locale}, {@code byte[]}, arrays, lists, sets and maps
 * (sets and maps in a stable order), {@link Optional}, records (by their components) and any type
 * that implements {@link CacheKeyPart}. Anything else is rejected: {@code toString()} of an
 * arbitrary object is not a stable identity (it may change, include memory addresses or leave out
 * fields), so it would silently mix up or never hit entries.
 *
 * <p>The format is versioned by {@link #FORMAT}: a change of format changes every key, so old
 * entries are simply not read again and expire with their TTL.
 */
public final class CacheKeys {

  /** Version of the key format; part of every method key built by the framework. */
  public static final String FORMAT = "k5";

  private static final String NULL = "-";

  private CacheKeys() {}

  /** Key of the given parts, joined with {@code :}. */
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
   * Encodes one value as {@code <type>=<value>} ({@code -} for {@code null}).
   *
   * @throws IllegalArgumentException for a type without a stable key (see the class comment)
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
      // BigDecimal keeps its scale: 1.0 and 1.00 are different values for equals() too
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
      // any other collection keeps its class: an ArrayDeque is never equal to a List
      case Collection<?> c -> "q(" + c.getClass().getName() + ")=[" + join(c) + "]";
      case Record r -> "r(" + r.getClass().getName() + ")=[" + components(r) + "]";
      default -> {
        if (value.getClass().isArray()) {
          List<Object> items = new ArrayList<>();
          for (int i = 0; i < Array.getLength(value); i++) {
            items.add(Array.get(value, i));
          }
          // the array class keeps the component type: int[]{7}, Integer[]{7}, long[]{7} and
          // int[][]{{7}} are different arguments and never share an entry
          yield "a(" + value.getClass().getName() + ")=[" + join(items) + "]";
        }
        throw new IllegalArgumentException(
            "No stable cache key for "
                + value.getClass().getName()
                + ": pass a record, a supported value or implement CacheKeyPart");
      }
    };
  }

  /** A part inside a container: its own separators are escaped once more. */
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
   * Escapes the key separator ({@code :}) and the escape character; inside containers also the
   * container syntax, so element boundaries cannot be forged by element contents.
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
