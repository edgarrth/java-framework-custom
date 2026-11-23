package pe.axiz.cli;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ejecuta herramientas externas (mvn, docker) con salida heredada. */
public interface ProcessRunner {

  /** Ejecuta un comando en un directorio y devuelve su código de salida. */
  int run(Path directory, List<String> command) throws IOException;

  /** Indica si un ejecutable está en el PATH. */
  boolean available(String executable);

  /** Procesos reales. */
  final class Local implements ProcessRunner {

    @Override
    @SuppressFBWarnings(
        value = "COMMAND_INJECTION",
        justification =
            "The local CLI intentionally executes the selected project's tools. "
                + "Commands are argv lists passed directly to ProcessBuilder without a shell; "
                + "no server input is evaluated.")
    public int run(Path directory, List<String> command) throws IOException {
      Process p = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
      try {
        return p.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        p.destroy();
        throw new IOException("Interrupted while running " + command.get(0), e);
      }
    }

    @Override
    public boolean available(String executable) {
      String path = System.getenv("PATH");
      if (path == null) {
        return false;
      }
      for (String dir : path.split(File.pathSeparator, -1)) {
        if (dir.isEmpty()) {
          continue; // una entrada vacía significa el directorio actual: nunca es de confianza
        }
        for (String name : List.of(executable, executable + ".exe", executable + ".cmd")) {
          if (Files.isExecutable(Path.of(dir, name))) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
