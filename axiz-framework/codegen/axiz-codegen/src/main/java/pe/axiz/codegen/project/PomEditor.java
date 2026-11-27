package pe.axiz.codegen.project;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Ediciones mínimas de un {@code pom.xml} que preservan el formato: agrega dependencias justo
 * previo al cierre {@code </dependencies>} del proyecto (nunca dentro de dependencyManagement ni de
 * perfiles) y actualiza la versión del padre.
 */
public final class PomEditor {

  private static final Pattern PARENT_VERSION =
      Pattern.compile("(<parent>.*?<version>)([^<]+)(</version>.*?</parent>)", Pattern.DOTALL);

  private PomEditor() {}

  /** Indica si el pom declara {@code groupId:artifactId} (en cualquier scope). */
  public static boolean hasDependency(String pom, String groupId, String artifactId) {
    Pattern p =
        Pattern.compile(
            "<dependency>\\s*<groupId>"
                + Pattern.quote(groupId)
                + "</groupId>\\s*<artifactId>"
                + Pattern.quote(artifactId)
                + "</artifactId>",
            Pattern.DOTALL);
    return p.matcher(pom).find();
  }

  /**
   * Agrega una dependencia (versión gestionada por el BOM) salvo que ya exista.
   *
   * @param scope scope de Maven, o null para compile
   */
  public static String addDependency(
      String pom, String groupId, String artifactId, @Nullable String scope) {
    if (hasDependency(pom, groupId, artifactId)) {
      return pom;
    }
    int end = projectDependenciesEnd(pom);
    if (end < 0) {
      throw new IllegalArgumentException("pom.xml has no <dependencies> section of the project");
    }
    String entry =
        "    <dependency>\n      <groupId>"
            + groupId
            + "</groupId>\n      <artifactId>"
            + artifactId
            + "</artifactId>\n"
            + (scope == null ? "" : "      <scope>" + scope + "</scope>\n")
            + "    </dependency>\n";
    int lineStart = pom.lastIndexOf('\n', end) + 1;
    return pom.substring(0, lineStart) + entry + pom.substring(lineStart);
  }

  /**
   * Reemplaza solo el artifact id del proyecto y deja intactas las coordenadas del padre y de las
   * dependencias.
   */
  public static String setArtifactId(String pom, String artifactId) {
    int parentEnd = pom.indexOf("</parent>");
    int start = parentEnd < 0 ? 0 : parentEnd + "</parent>".length();
    Matcher matcher = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(pom);
    if (!matcher.find(start)) throw new IllegalArgumentException("Missing project artifactId");
    return pom.substring(0, matcher.start(1)) + artifactId + pom.substring(matcher.end(1));
  }

  /** Reemplaza la versión del padre. */
  public static String setParentVersion(String pom, String version) {
    Matcher m = PARENT_VERSION.matcher(pom);
    if (!m.find()) {
      throw new IllegalArgumentException("pom.xml has no parent");
    }
    return pom.substring(0, m.start(2)) + version + pom.substring(m.end(2));
  }

  /** Versión del padre, o null. */
  public static @Nullable String parentVersion(String pom) {
    Matcher m = PARENT_VERSION.matcher(pom);
    return m.find() ? m.group(2).strip() : null;
  }

  /**
   * Índice del cierre {@code </dependencies>} del proyecto (fuera de la gestión de dependencias y
   * de los perfiles).
   */
  static int projectDependenciesEnd(String pom) {
    String masked = mask(mask(mask(pom, "dependencyManagement"), "profiles"), "build");
    return masked.lastIndexOf("</dependencies>");
  }

  private static String mask(String text, String element) {
    StringBuilder b = new StringBuilder(text);
    Matcher m =
        Pattern.compile("<" + element + ">.*?</" + element + ">", Pattern.DOTALL).matcher(text);
    while (m.find()) {
      for (int i = m.start(); i < m.end(); i++) {
        if (b.charAt(i) != '\n') {
          b.setCharAt(i, ' ');
        }
      }
    }
    return b.toString();
  }
}
