package pe.axiz.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code axiz local up|down}: the local dependencies of the service (Docker Compose + LocalStack).
 */
@Command(
    mixinStandardHelpOptions = true,
    name = "local",
    description = "Starts or stops the local dependencies (compose.yaml).",
    subcommands = {LocalCommand.Up.class, LocalCommand.Down.class})
class LocalCommand implements Runnable {

  @picocli.CommandLine.Spec picocli.CommandLine.Model.CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  abstract static class Compose extends BaseCommand implements Callable<Integer> {

    abstract List<String> arguments();

    @Override
    public Integer call() throws IOException {
      if (!Files.isRegularFile(project().resolve("compose.yaml"))) {
        throw new IllegalArgumentException("No compose.yaml in " + project());
      }
      if (!environment.processes().available("docker")) {
        throw new IllegalArgumentException("Docker is not installed or not on the PATH");
      }
      List<String> command = new ArrayList<>(List.of("docker", "compose"));
      command.addAll(arguments());
      int code = environment.processes().run(project(), command);
      if (code == 0) {
        ok("docker " + String.join(" ", command.subList(1, command.size())));
        return ExitCodes.OK;
      }
      fail("docker compose exited with " + code);
      return ExitCodes.PROBLEMS;
    }
  }

  /** Starts the dependencies. */
  @Command(
      mixinStandardHelpOptions = true,
      name = "up",
      description = "docker compose up -d --wait")
  static class Up extends Compose {
    @Override
    List<String> arguments() {
      return List.of("up", "-d", "--wait");
    }

    @Override
    public Integer call() throws IOException {
      Integer code = super.call();
      if (code == ExitCodes.OK) {
        out().println("Run the service: mvn spring-boot:run -Dspring-boot.run.profiles=local");
        out().println("Grafana: http://localhost:3000");
      }
      return code;
    }
  }

  /** Stops the dependencies. */
  @Command(mixinStandardHelpOptions = true, name = "down", description = "docker compose down")
  static class Down extends Compose {

    @Option(
        names = {"-v", "--volumes"},
        description = "Also delete the data volumes.")
    boolean volumes;

    @Override
    List<String> arguments() {
      return volumes ? List.of("down", "--volumes") : List.of("down");
    }
  }
}
