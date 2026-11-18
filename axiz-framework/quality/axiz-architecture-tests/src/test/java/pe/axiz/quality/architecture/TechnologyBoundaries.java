package pe.axiz.quality.architecture;

import java.util.Map;
import java.util.Set;

/** Shared class/POM policy; every exception must have a documented reason at this declaration. */
final class TechnologyBoundaries {
  // AWS database authentication implements the neutral credential contract owned by the R2DBC kit.
  static final Map<String, Set<String>> EXCEPTIONS =
      Map.of("databaseauth.aws", Set.of("r2dbc"), "outbox.r2dbc", Set.of("reactor"));

  private TechnologyBoundaries() {}

  static String technology(String packageName) {
    if (packageName.equals("pe.axiz.adapter.aws") || packageName.startsWith("pe.axiz.adapter.aws."))
      return "aws";
    String[] parts = packageName.split("\\.");
    if (parts.length >= 4 && packageName.startsWith("pe.axiz.kit.")) return parts[3];
    if (parts.length >= 5 && packageName.startsWith("pe.axiz.adapter.")) return parts[4];
    return "";
  }

  static String module(String packageName) {
    if (packageName.equals("pe.axiz.adapter.aws") || packageName.startsWith("pe.axiz.adapter.aws."))
      return "aws";
    String[] parts = packageName.split("\\.");
    if (packageName.startsWith("pe.axiz.kit.") && parts.length >= 4) return "kit." + parts[3];
    if (packageName.startsWith("pe.axiz.adapter.") && parts.length >= 5)
      return parts[3] + "." + parts[4];
    return "";
  }

  static boolean allowed(String sourceModule, String technology, String targetKit) {
    return (targetKit.equals("sql") && Set.of("jdbc", "r2dbc").contains(technology))
        || technology.equals(targetKit)
        || EXCEPTIONS.getOrDefault(sourceModule, Set.of()).contains(targetKit);
  }
}
