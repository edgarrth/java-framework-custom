package pe.axiz.cli;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Opciones comunes y utilidades de salida. */
abstract class BaseCommand implements AxizCli.EnvironmentAware {

  @Spec CommandSpec spec;

  @Option(
      names = {"-d", "--dir"},
      defaultValue = ".",
      description = "Project directory (default: current directory).")
  Path dir = Path.of(".");

  AxizCli.Environment environment = AxizCli.Environment.system();

  @Override
  public void environment(AxizCli.Environment environment) {
    this.environment = environment;
  }

  PrintWriter out() {
    return spec.commandLine().getOut();
  }

  PrintWriter err() {
    return spec.commandLine().getErr();
  }

  Path project() {
    return dir.toAbsolutePath().normalize();
  }

  void ok(String message) {
    out().println(spec.commandLine().getColorScheme().string("@|green ✔|@ ") + message);
  }

  void warn(String message) {
    out().println(spec.commandLine().getColorScheme().string("@|yellow !|@ ") + message);
  }

  void fail(String message) {
    out().println(spec.commandLine().getColorScheme().string("@|red ✘|@ ") + message);
  }

  /**
   * Deja las fuentes Java de un proyecto tal como las espera su {@code spotless:check}. El jar de
   * la CLI las formatea mientras las escribe; donde google-java-format no puede ejecutarse (el
   * binario nativo), el propio Maven wrapper del proyecto ejecuta {@code spotless:apply}.
   */
  void formatSources(Path project) {
    if (environment.javaFormatter().getAsBoolean()) {
      return;
    }
    Path wrapper = project.resolve(File.separatorChar == '\\' ? "mvnw.cmd" : "mvnw");
    String maven = Files.isRegularFile(wrapper) ? wrapper.toString() : "mvn";
    if (maven.equals("mvn") && !environment.processes().available("mvn")) {
      warn("Sources not formatted (no Maven found): run mvn spotless:apply before mvn verify");
      return;
    }
    out().println("Formatting the sources (spotless:apply)...");
    try {
      int code = environment.processes().run(project, List.of(maven, "-q", "-B", "spotless:apply"));
      if (code != 0) {
        warn("spotless:apply exited with " + code + ": run it before mvn verify");
      }
    } catch (IOException e) {
      warn("Could not run spotless:apply (" + e.getMessage() + "): run it before mvn verify");
    }
  }
}
