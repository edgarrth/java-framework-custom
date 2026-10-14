package pe.axiz.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Naming conventions for generated code. */
public final class Names {

  private static final Pattern WORDS = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
  private static final Pattern ARTIFACT = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
  private static final Pattern PACKAGE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

  private Names() {}

  public static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : WORDS.split(text.strip())) {
      if (!w.isEmpty()) {
        out.add(w.toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** {@code create-payment} or {@code createPayment} becomes {@code CreatePayment}. */
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

  /** Last segment of a dotted name (package segment safe). */
  public static String packageSegment(String text) {
    String s = String.join("", words(text));
    return s.isEmpty() || Character.isDigit(s.charAt(0)) ? "x" + s : s;
  }

  private static final java.util.Set<String> KEYWORDS =
      java.util.Set.of(
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
   * A safe Java identifier for a contract name: camelCase, never a keyword nor one of the names the
   * generated code uses ({@code body}, {@code page}).
   */
  public static String identifier(String text) {
    String c = camel(text);
    if (c.isEmpty() || !Character.isJavaIdentifierStart(c.charAt(0))) {
      c = "p" + pascal(text);
    }
    return KEYWORDS.contains(c) ? c + "Value" : c;
  }

  /**
   * Prefix of the error codes of a service ({@code SYSTEM-SERVICE}, followed by {@code -NNNN}): the
   * last segment of the group and the service name, upper case and alphanumeric.
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
    return PACKAGE.matcher(text).matches() && text.length() <= 120;
  }

  public static String packagePath(String pkg) {
    return pkg.replace('.', '/');
  }
}
