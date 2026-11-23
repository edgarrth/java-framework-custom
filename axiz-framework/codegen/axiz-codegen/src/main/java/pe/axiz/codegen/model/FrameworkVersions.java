package pe.axiz.codegen.model;

import java.util.regex.Pattern;

/** Las coordenadas del framework comienzan en 2.0.0, la primera release de Axiz. */
public final class FrameworkVersions {
  private static final Pattern RELEASE = Pattern.compile("(?:[2-9]|[1-9][0-9]+)\\.[0-9]+\\.[0-9]+");
  private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9.]+");

  private FrameworkVersions() {}

  public static boolean isSupported(String version) {
    int dash = version.indexOf('-');
    String release = dash < 0 ? version : version.substring(0, dash);
    return RELEASE.matcher(release).matches()
        && (dash < 0 || SUFFIX.matcher(version.substring(dash + 1)).matches());
  }

  public static void requireSupported(String version) {
    if (!isSupported(version))
      throw new IllegalArgumentException("Axiz framework version must be 2.0.0 or later");
  }
}
