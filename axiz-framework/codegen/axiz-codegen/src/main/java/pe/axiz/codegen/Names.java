package pe.axiz.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/** Convenciones de nombres para el código generado. */
public final class Names {

  private static final Pattern WORDS = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
  // Cada carácter coincide con exactamente una alternativa y un separador debe ir seguido de un
  // inicio válido, por lo que la coincidencia es lineal en la longitud del texto (sin repeticiones
  // anidadas).
  private static final Pattern ARTIFACT = Pattern.compile("^[a-z](?:[a-z0-9]|-(?=[a-z0-9]))*$");
  private static final Pattern PACKAGE = Pattern.compile("^[a-z](?:[a-z0-9_]|\\.(?=[a-z]))*$");

  private Names() {}

  public static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : WORDS.split(text.strip(), -1)) {
      if (!w.isEmpty()) {
        out.add(w.toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** {@code create-payment} o {@code createPayment} se convierte en {@code CreatePayment}. */
  public static String pascal(String text) {
    StringBuilder b = new StringBuilder();
    for (String w : words(text)) {
      b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
    }
    return b.toString();
  }

  public static String camel(String text) {
    String p = pascal(text);
    return p.isEmpty() ? p : Character.toLowerCase(p.charAt(0)) + p.substring(1);
  }

  public static String kebab(String text) {
    return String.join("-", words(text));
  }

  public static String snake(String text) {
    return String.join("_", words(text));
  }

  /** Último segmento de un nombre con puntos (seguro como segmento de paquete). */
  public static String packageSegment(String text) {
    String s = String.join("", words(text));
    return s.isEmpty() || Character.isDigit(s.charAt(0)) ? "x" + s : s;
  }

  private static final Set<String> KEYWORDS =
      Set.of(
          "abstract",
          "assert",
          "boolean",
          "break",
          "byte",
          "case",
          "catch",
          "char",
          "class",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "goto",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "native",
          "new",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "strictfp",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "transient",
          "try",
          "void",
          "volatile",
          "while",
          "true",
          "false",
          "null",
          "var",
          "record",
          "yield",
          "sealed",
          "permits",
          "body",
          "page");

  /**
   * Un identificador Java seguro para un nombre del contrato: camelCase, nunca una palabra
   * reservada ni uno de los nombres que usa el código generado ({@code body}, {@code page}).
   */
  public static String identifier(String text) {
    String c = camel(text);
    if (c.isEmpty() || !Character.isJavaIdentifierStart(c.charAt(0))) {
      c = "p" + pascal(text);
    }
    return KEYWORDS.contains(c) ? c + "Value" : c;
  }

  /**
   * Prefijo de los códigos de error de un servicio ({@code SYSTEM-SERVICE}, seguido de {@code
   * -NNNN}): el último segmento del grupo y el nombre del servicio, en mayúsculas y alfanumérico.
   */
  public static String errorPrefix(String groupId, String name) {
    String system =
        groupId
            .substring(groupId.lastIndexOf('.') + 1)
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]", "");
    String service = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (system.isEmpty() || !Character.isLetter(system.charAt(0))) {
      system = "APP" + system;
    }
    system = system.substring(0, Math.min(10, system.length()));
    if (service.length() < 2) {
      service = service + "SVC";
    }
    return system + "-" + service.substring(0, Math.min(20, service.length()));
  }

  public static boolean isArtifactId(String text) {
    return ARTIFACT.matcher(text).matches() && text.length() <= 60;
  }

  public static boolean isPackage(String text) {
    return PACKAGE.matcher(text).matches() && text.length() <= 120 && SourceVersion.isName(text);
  }

  public static String packagePath(String pkg) {
    return pkg.replace('.', '/');
  }
}
