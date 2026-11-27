package pe.axiz.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
  @Test
  void argumentsArePassedLiterallyAndExitStatusAndWorkingDirectoryArePreserved(
      @TempDir Path directory) throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path program = directory.resolve("ExitProbe.java");
    Files.writeString(
        program,
        "class ExitProbe { public static void main(String[] a) throws Exception { "
            + "java.nio.file.Files.writeString(java.nio.file.Path.of(\"result\"), a[0]); "
            + "System.exit(7); } }");
    String literal = "$(touch injected); & no-shell";
    assertEquals(
        7,
        new ProcessRunner.Local()
            .run(directory, List.of(java.toString(), program.toString(), literal)));
    assertEquals(literal, Files.readString(directory.resolve("result")));
    assertFalse(Files.exists(directory.resolve("injected")));
    assertTrue(new ProcessRunner.Local().available("java"));
    assertFalse(new ProcessRunner.Local().available("axiz-command-that-does-not-exist-80349"));
    assertThrows(
        IOException.class,
        () ->
            new ProcessRunner.Local()
                .run(directory, List.of(directory.resolve("missing-command").toString())));
  }

  @Test
  void interruptionPreservesTheSignalAndTerminatesTheChild(@TempDir Path directory)
      throws Exception {
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    Path program = directory.resolve("WaitProbe.java");
    Files.writeString(
        program,
        "class WaitProbe { public static void main(String[] a) throws Exception { Thread.sleep(60000); } }");
    var interrupted = new AtomicBoolean();
    var rejected = new AtomicBoolean();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  try {
                    new ProcessRunner.Local()
                        .run(directory, List.of(java.toString(), program.toString()));
                  } catch (IOException expected) {
                    rejected.set(expected.getMessage().contains("Interrupted while running"));
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });
    caller.join(10000);
    assertFalse(caller.isAlive());
    assertTrue(rejected.get());
    assertTrue(interrupted.get());
  }
}
