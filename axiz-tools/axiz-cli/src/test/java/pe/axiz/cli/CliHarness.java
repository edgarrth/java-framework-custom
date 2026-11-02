package pe.axiz.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Runs the CLI in-process with captured output and fake processes. */
final class CliHarness {

  /** Records commands instead of running them. */
  static final class FakeProcesses implements ProcessRunner {
    final List<List<String>> commands = new ArrayList<>();
    final Set<String> installed;
    int exitCode;
    java.util.function.Consumer<Path> beforeRun = ignored -> {};
    @Nullable IOException failure;

    FakeProcesses(Set<String> installed) {
      this.installed = new java.util.HashSet<>(installed);
    }

    @Override
    public int run(Path directory, List<String> command) throws IOException {
      beforeRun.accept(directory);
      commands.add(command);
      IOException e = failure;
      if (e != null) {
        throw e;
      }
      return exitCode;
    }

    @Override
    public boolean available(String executable) {
      return installed.contains(executable);
    }
  }

  final FakeProcesses processes = new FakeProcesses(Set.of("mvn", "docker"));
  String input = "";
  Map<String, String> env = Map.of();
  // the tests run with the compiler exports of the CLI jar, so google-java-format runs in-process
  boolean javaFormatter = true;
  final StringWriter out = new StringWriter();
  final StringWriter err = new StringWriter();

  int run(String... args) {
    AxizCli.Environment environment =
        new AxizCli.Environment(
            processes,
            new BufferedReader(new StringReader(input)),
            Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC),
            env,
            () -> javaFormatter);
    CommandLine cli = AxizCli.commandLine(environment);
    cli.setOut(new PrintWriter(out, true));
    cli.setErr(new PrintWriter(err, true));
    cli.setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.OFF));
    return cli.execute(args);
  }

  String output() {
    return out + "\n" + err;
  }

  static Path contract(Path dir) throws IOException {
    try (InputStream in = CliHarness.class.getResourceAsStream("/orders.yaml")) {
      Path file = dir.resolve("orders.yaml");
      Files.writeString(file, new String(in.readAllBytes(), StandardCharsets.UTF_8));
      return file;
    }
  }

  /** Creates a business service in {@code dir} and returns its folder. */
  static Path service(Path dir, String... extra) throws IOException {
    CliHarness h = new CliHarness();
    List<String> args =
        new ArrayList<>(
            List.of(
                "new",
                "orders",
                "--archetype",
                "business",
                "--openapi",
                contract(dir).toString(),
                "--output",
                dir.toString()));
    args.addAll(List.of(extra));
    int code = h.run(args.toArray(String[]::new));
    if (code != 0) {
      throw new AssertionError(h.output());
    }
    return dir.resolve("orders");
  }
}
