package pe.axiz.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Runs external tools (mvn, docker) with inherited output. */
public interface ProcessRunner {

  /** Runs a command in a directory and returns its exit code. */
  int run(Path directory, List<String> command) throws IOException;

  /** Whether an executable is on the PATH. */
  boolean available(String executable);

  /** Real processes. */
  final class Local implements ProcessRunner {

    @Override
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
      String path = java.lang.System.getenv("PATH");
      if (path == null) {
        return false;
      }
      for (String dir : path.split(java.io.File.pathSeparator, -1)) {
        if (dir.isEmpty()) {
          continue; // an empty entry means the current directory: never trusted
        }
        for (String name : List.of(executable, executable + ".exe", executable + ".cmd")) {
          if (java.nio.file.Files.isExecutable(Path.of(dir, name))) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
