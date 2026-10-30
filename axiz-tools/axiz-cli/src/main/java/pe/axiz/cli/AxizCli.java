package pe.axiz.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import pe.axiz.codegen.render.JavaFormatter;
import picocli.CommandLine;

/** Entry point of the {@code axiz} binary. */
public final class AxizCli {

  private AxizCli() {}

  public static void main(String[] args) {
    System.exit(commandLine(Environment.system()).execute(args));
  }

  /** The command tree wired to an environment (processes, input, clock); tests pass their own. */
  public static CommandLine commandLine(Environment environment) {
    CommandLine cli = new CommandLine(new AxizCommand(), new CommandFactory(environment));
    cli.setCaseInsensitiveEnumValuesAllowed(true);
    cli.setExecutionExceptionHandler(
        (e, commandLine, parseResult) -> {
          if (e instanceof IllegalArgumentException || e instanceof java.io.IOException) {
            commandLine
                .getErr()
                .println(commandLine.getColorScheme().errorText("error: " + e.getMessage()));
            return ExitCodes.PROBLEMS;
          }
          throw e;
        });
    return cli;
  }

  /**
   * What commands use from the outside world.
   *
   * @param processes runs external tools (mvn, docker)
   * @param input interactive input
   * @param clock time source (migration versions)
   * @param env environment variables
   * @param javaFormatter whether this process formats Java sources itself (google-java-format runs
   *     in the CLI jar, not in the native binary)
   */
  public record Environment(
      ProcessRunner processes,
      BufferedReader input,
      Clock clock,
      java.util.Map<String, String> env,
      BooleanSupplier javaFormatter) {
    public Environment {
      env = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(env));
    }

    public static Environment system() {
      return new Environment(
          new ProcessRunner.Local(),
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
          Clock.systemUTC(),
          System.getenv(),
          JavaFormatter::available);
    }
  }

  /** Gives commands that need it the environment. */
  static final class CommandFactory implements CommandLine.IFactory {
    private final Environment environment;
    private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

    CommandFactory(Environment environment) {
      this.environment = environment;
    }

    @Override
    public <K> K create(Class<K> type) throws Exception {
      if (EnvironmentAware.class.isAssignableFrom(type)) {
        K instance = fallback.create(type);
        ((EnvironmentAware) instance).environment(environment);
        return instance;
      }
      return fallback.create(type);
    }
  }

  /** Commands that use the environment. */
  interface EnvironmentAware {
    void environment(Environment environment);
  }
}
