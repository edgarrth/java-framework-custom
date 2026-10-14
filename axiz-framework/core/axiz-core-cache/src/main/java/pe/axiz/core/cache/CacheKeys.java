package pe.axiz.core.cache;

import java.util.StringJoiner;
import org.jspecify.annotations.Nullable;

/** Builds cache keys from method arguments; values containing separators are escaped. */
public final class CacheKeys {

  private CacheKeys() {}

  public static String of(@Nullable Object... parts) {
    StringJoiner j = new StringJoiner(":");
    for (Object p : parts) {
      j.add(p == null ? "_" : p.toString().replace("\\", "\\\\").replace(":", "\\:"));
    }
    return j.toString();
  }
}
