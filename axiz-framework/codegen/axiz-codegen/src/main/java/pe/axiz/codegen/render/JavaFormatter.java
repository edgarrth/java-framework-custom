package pe.axiz.codegen.render;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Formats generated Java sources with google-java-format (and removes unused imports) when it is on
 * the classpath, so generated projects pass {@code spotless:check} from the first build. The
 * initializr ships it; without it sources are returned as rendered (templates already follow the
 * style) and {@code ./mvnw spotless:apply} finishes the job.
 */
public final class JavaFormatter {

  private static final @Nullable Method FORMAT = lookup();
  private static final @Nullable Object FORMATTER = instance();

  private JavaFormatter() {}

  /** Whether google-java-format is available. */
  public static boolean available() {
    return FORMAT != null && FORMATTER != null;
  }

  /** Formats one source; returns it unchanged when the formatter is missing or rejects it. */
  public static String format(String source) {
    Method method = FORMAT;
    Object formatter = FORMATTER;
    if (method == null || formatter == null) {
      return source;
    }
    try {
      Object out = method.invoke(formatter, source);
      return out instanceof String s ? s : source;
    } catch (IllegalAccessException
        | InvocationTargetException
        | RuntimeException
        | LinkageError e) {
      return source;
    }
  }

  /** Formats every {@code .java} entry of a rendered file map. */
  public static Map<String, byte[]> formatAll(Map<String, byte[]> files) {
    if (!available()) {
      return files;
    }
    Map<String, byte[]> out = new LinkedHashMap<>();
    files.forEach(
        (path, content) ->
            out.put(
                path,
                path.endsWith(".java")
                    ? format(new String(content, java.nio.charset.StandardCharsets.UTF_8))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : content));
    return out;
  }

  private static @Nullable Method lookup() {
    try {
      Class<?> type = Class.forName("com.google.googlejavaformat.java.Formatter");
      return type.getMethod("formatSourceAndFixImports", String.class);
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  private static @Nullable Object instance() {
    try {
      return Class.forName("com.google.googlejavaformat.java.Formatter")
          .getDeclaredConstructor()
          .newInstance();
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }
}
