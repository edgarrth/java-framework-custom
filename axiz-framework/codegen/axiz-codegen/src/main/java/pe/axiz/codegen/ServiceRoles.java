package pe.axiz.codegen;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Closed naming and synchronous communication matrix from architecture contract. */
public final class ServiceRoles {
  private ServiceRoles() {}

  public static final Set<String> ROLES =
      Set.of("business", "core", "gateway", "ux", "support", "event-processor", "batch");
  private static final Map<String, Set<String>> CALLS =
      Map.of(
          "business", Set.of("business", "core", "gateway", "support"),
          "core", Set.of("internal-system"),
          "gateway", Set.of("external-system"),
          "ux", Set.of("business", "support"),
          "support", Set.of("commodity-provider"),
          "event-processor", Set.of("business", "core", "gateway", "support"),
          "batch", Set.of("business", "core", "gateway", "support"));

  public static void requireCall(String from, String to) {
    if (!CALLS.getOrDefault(from, Set.of()).contains(to))
      throw new IllegalArgumentException(
          "Synchronous call from " + from + " to " + to + " is forbidden by architecture contract");
  }

  public static boolean validName(String role, String name) {
    if (!Names.isArtifactId(name)) return false;
    String suffix =
        switch (role) {
          case "core", "gateway", "ux", "support", "batch" -> "-" + role;
          case "event-processor" -> "-processor";
          case "business" -> "";
          default -> null;
        };
    if (suffix == null
        || (!suffix.isEmpty() && (!name.endsWith(suffix) || name.length() <= suffix.length())))
      return false;
    String noun = suffix.isEmpty() ? name : name.substring(0, name.length() - suffix.length());
    Set<String> reserved =
        Set.of(
            "crud",
            "business",
            "experience",
            "integration",
            "hexagonal",
            "layered",
            "cqrs",
            "eda",
            "bff",
            "core",
            "gateway",
            "ux",
            "support",
            "processor",
            "batch");
    return Arrays.stream(noun.split("-")).noneMatch(reserved::contains);
  }
}
