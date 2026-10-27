package pe.axiz.codegen.render;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Formats generated Java sources with google-java-format (and removes unused imports) when it is on
 * the classpath and can run, so generated projects pass {@code spotless:check} from the first
 * build. The initializr and the CLI jar ship it; google-java-format reads the compiler internals,
 * so the JVM must export them ({@code --add-exports jdk.compiler/...}: the initializr image sets
 * the flags, the CLI jar declares them in its manifest). Where it cannot run, such as the native
 * CLI, sources are returned as rendered and the caller runs {@code spotless:apply}.
 */
public final class JavaFormatter {

  private static final @Nullable Method FORMAT = lookup();
  private static final @Nullable Object FORMATTER = instance();
  private static final boolean WORKS = probe();

  private JavaFormatter() {}

  /**
   * Whether google-java-format is on the classpath and runs: it is tried once on a small source,
   * because without the exports of jdk.compiler it loads but fails on the first format.
   */
  public static boolean available() {
    return WORKS;
  }

  /** Formats one source; returns it unchanged when the formatter is missing or rejects it. */
  public static String format(String source) {
    Method method = FORMAT;
    Object formatter = FORMATTER;
    if (!WORKS || method == null || formatter == null) {
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

  private static boolean probe() {
    Method method = FORMAT;
    Object formatter = FORMATTER;
    if (method == null || formatter == null) {
      return false;
    }
    try {
      return method.invoke(formatter, "class Probe {  }\n") instanceof String s
          && s.equals("class Probe {}\n");
    } catch (IllegalAccessException
        | InvocationTargetException
        | RuntimeException
        | LinkageError e) {
      return false;
    }
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
