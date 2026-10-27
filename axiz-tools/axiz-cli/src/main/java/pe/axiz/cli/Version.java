package pe.axiz.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine;

/** Version of the CLI and of the axiz framework it generates for. */
final class Version implements CommandLine.IVersionProvider {

  private static final Properties PROPERTIES = load();

  static String cli() {
    return PROPERTIES.getProperty("version", "dev");
  }

  static String axiz() {
    String v = PROPERTIES.getProperty("axizVersion", "");
    return v.isBlank() || v.startsWith("${") ? "1.0.0" : v;
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
      // version stays unknown
    }
    return p;
  }
}
