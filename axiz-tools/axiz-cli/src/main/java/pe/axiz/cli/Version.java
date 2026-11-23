package pe.axiz.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine;

/** Versión de la CLI y del framework axiz para el que genera. */
final class Version implements CommandLine.IVersionProvider {

  private static final Properties PROPERTIES = load();

  static String cli() {
    return PROPERTIES.getProperty("version", "dev");
  }

  static String axiz() {
    String v = PROPERTIES.getProperty("axizVersion", "");
    return v.isBlank() || v.startsWith("${") ? "2.0.0" : v;
  }

  static String rewritePlugin() {
    String value = PROPERTIES.getProperty("rewritePluginVersion", "");
    if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
      throw new IllegalStateException("Missing curated OpenRewrite Maven plugin version");
    }
    return value;
  }

  @Override
  public String[] getVersion() {
    return new String[] {
      "axiz " + cli() + " (framework " + axiz() + ")", "Java " + Runtime.version()
    };
  }

  private static Properties load() {
    Properties p = new Properties();
    try (InputStream in = Version.class.getResourceAsStream("/axiz/cli.properties")) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      // la versión queda desconocida
    }
    return p;
  }
}
