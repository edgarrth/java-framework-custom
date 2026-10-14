package pe.axiz.core.storage;

import java.util.regex.Pattern;

/** Validated object key: relative, no {@code ..} segments, no control characters. */
public final class ObjectKey {

  private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9!_.*'()/=-]{1,1024}$");

  private ObjectKey() {}

  public static String validate(String key) {
    if (key == null || !SAFE.matcher(key).matches() || key.startsWith("/") || key.contains("//")) {
      throw new IllegalArgumentException("Invalid object key");
    }
    for (String segment : key.split("/")) {
      if (segment.equals("..") || segment.equals(".")) {
        throw new IllegalArgumentException("Invalid object key");
      }
    }
    return key;
  }
}
