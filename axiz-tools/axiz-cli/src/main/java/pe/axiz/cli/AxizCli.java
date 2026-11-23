package pe.axiz.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import pe.axiz.codegen.render.JavaFormatter;
import picocli.CommandLine;

/** Punto de entrada del binario {@code axiz}. */
public final class AxizCli {

  private AxizCli() {}

  public static void main(String[] args) {
    System.exit(commandLine(Environment.system()).execute(args));
  }

  /**
   * El árbol de comandos conectado a un entorno (procesos, entrada, reloj); las pruebas pasan el
   * suyo.
   */
  public static CommandLine commandLine(Environment environment) {
    CommandLine cli = new CommandLine(new AxizCommand(), new CommandFactory(environment));
    cli.setCaseInsensitiveEnumValuesAllowed(true);
    cli.setExecutionExceptionHandler(
        (e, commandLine, parseResult) -> {
          if (e instanceof IllegalArgumentException || e instanceof IOException) {
            commandLine
                .getErr()
                .println(commandLine.getColorScheme().errorText("error: " + e.getMessage()));
            return ExitCodes.PROBLEMS;
          }
          if (environment.env().containsKey("AXIZ_DEBUG")) throw e;
          // un defecto de la CLI o de sus plantillas: una línea accionable y la traza bajo demanda
          commandLine
              .getErr()
              .println(
                  commandLine
                      .getColorScheme()
                      .errorText(
                          "error: axiz "
                              + Version.cli()
                              + " failed unexpectedly ("
                              + e.getClass().getSimpleName()
                              + (e.getMessage() == null ? "" : ": " + e.getMessage())
                              + "). Report it with the command you ran; set AXIZ_DEBUG=1 to"
                              + " print the stack trace."));
          return ExitCodes.INTERNAL;
        });
    return cli;
  }

  /**
   * Lo que los comandos usan del mundo exterior.
   *
   * @param processes ejecuta herramientas externas (mvn, docker)
   * @param input entrada interactiva
   * @param clock fuente de tiempo (versiones de migración)
   * @param env variables de entorno
   * @param javaFormatter indica si este proceso formatea por sí mismo las fuentes Java
   *     (google-java-format se ejecuta en el jar de la CLI, no en el binario nativo)
   */
  public record Environment(
      ProcessRunner processes,
      BufferedReader input,
      Clock clock,
      Map<String, String> env,
      BooleanSupplier javaFormatter) {
    public Environment {
      env = Collections.unmodifiableMap(new LinkedHashMap<>(env));
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

  /** Entrega el entorno a los comandos que lo necesitan. */
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

  /** Comandos que usan el entorno. */
  interface EnvironmentAware {
    void environment(Environment environment);
  }
}
