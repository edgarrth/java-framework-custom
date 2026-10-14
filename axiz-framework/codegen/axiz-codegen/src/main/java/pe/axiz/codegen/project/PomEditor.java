package pe.axiz.codegen.project;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Minimal, formatting-preserving edits of a {@code pom.xml}: adds dependencies before the closing
 * {@code </dependencies>} of the project (never inside dependencyManagement or profiles) and
 * updates the parent version.
 */
public final class PomEditor {

  private static final Pattern PARENT_VERSION =
      Pattern.compile("(<parent>.*?<version>)([^<]+)(</version>.*?</parent>)", Pattern.DOTALL);

  private PomEditor() {}

  /** Whether the pom declares {@code groupId:artifactId} (in any scope). */
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
   * Adds a dependency (version managed by the BOM) unless present.
   *
   * @param scope Maven scope, or null for compile
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

  /** Replaces the version of the parent. */
  public static String setParentVersion(String pom, String version) {
    Matcher m = PARENT_VERSION.matcher(pom);
    if (!m.find()) {
      throw new IllegalArgumentException("pom.xml has no parent");
    }
    return pom.substring(0, m.start(2)) + version + pom.substring(m.end(2));
  }

  /** Version of the parent, or null. */
  public static @Nullable String parentVersion(String pom) {
    Matcher m = PARENT_VERSION.matcher(pom);
    return m.find() ? m.group(2).strip() : null;
  }

  /** Index of the project's closing {@code </dependencies>} (outside management and profiles). */
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
