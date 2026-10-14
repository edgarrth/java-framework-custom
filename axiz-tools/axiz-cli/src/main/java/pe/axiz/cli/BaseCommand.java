package pe.axiz.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Common options and output helpers. */
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
}
