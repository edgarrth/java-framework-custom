package pe.axiz.codegen.render;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Formatea las fuentes Java generadas con google-java-format (y elimina los imports sin usar)
 * cuando está en el classpath y puede ejecutarse, de modo que los proyectos generados superan
 * {@code spotless:check} desde el primer build. El initializr y el jar de la CLI lo incluyen;
 * google-java-format lee los internos del compilador, por lo que la JVM debe exportarlos ({@code
 * --add-exports jdk.compiler/...}: la imagen del initializr fija los flags y el jar de la CLI los
 * declara en su manifiesto). Donde no puede ejecutarse, como en la CLI nativa, las fuentes se
 * devuelven tal como se renderizaron y el invocador ejecuta {@code spotless:apply}.
 */
public final class JavaFormatter {

  private static final @Nullable Method FORMAT = lookup();
  private static final @Nullable Object FORMATTER = instance();
  private static final boolean WORKS = probe();

  private JavaFormatter() {}

  /**
   * Indica si google-java-format está en el classpath y funciona: se prueba una vez con una fuente
   * pequeña, porque sin los exports de jdk.compiler se carga, pero falla en el primer formateo.
   */
  public static boolean available() {
    return WORKS;
  }

  /** Formatea una fuente; la devuelve sin cambios cuando el formateador falta o la rechaza. */
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

  /** Formatea cada entrada {@code .java} de un mapa de archivos renderizados. */
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
                    ? format(new String(content, StandardCharsets.UTF_8))
                        .getBytes(StandardCharsets.UTF_8)
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
