package pe.axiz.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshingFileSecretsTest {
  private static void replace(Path file, byte[] bytes) throws Exception {
    Path temporary = file.resolveSibling("..update-" + java.util.UUID.randomUUID());
    try {
      Files.write(temporary, bytes);
      Files.move(
          temporary,
          file,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void eventually(BooleanSupplier condition) throws InterruptedException {
    org.awaitility.Awaitility.await()
        .pollDelay(Duration.ZERO)
        .pollInterval(Duration.ofMillis(10))
        .atMost(Duration.ofSeconds(5))
        .until(condition::getAsBoolean);
  }

  @Test
  void startupSnapshotRotationRemovalAndClosure(@TempDir Path directory) throws Exception {
    Path secret = directory.resolve("payments.password");
    Files.writeString(secret, "first\n");
    var resolver = new RefreshingFileSecretResolver(directory, Duration.ofMillis(25));
    try (resolver) {
      assertEquals(
          "first", resolver.require("payments/password").toCompletableFuture().join().reveal());
      replace(secret, "rotated".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      eventually(
          () ->
              resolver
                  .require("payments/password")
                  .toCompletableFuture()
                  .join()
                  .reveal()
                  .equals("rotated"));
      Files.delete(secret);
      eventually(
          () -> resolver.resolve("payments/password").toCompletableFuture().join().isEmpty());
      var invalid = resolver.resolve("../escape").toCompletableFuture();
      assertInstanceOf(
          IllegalArgumentException.class,
          assertThrows(CompletionException.class, invalid::join).getCause());
    }
    resolver.close();
    var closed = resolver.resolve("payments/password").toCompletableFuture();
    assertInstanceOf(
        IllegalStateException.class,
        assertThrows(CompletionException.class, closed::join).getCause());
  }

  @Test
  void readsKubernetesStyleSymlinksAndRejectsOversizedAndInvalidUtf8AtStartup(
      @TempDir Path directory) throws Exception {
    Path data = Files.createDirectory(directory.resolve("..data"));
    Files.writeString(data.resolve("key"), "mounted");
    Files.createSymbolicLink(directory.resolve("key"), Path.of("..data/key"));
    try (var resolver = new RefreshingFileSecretResolver(directory, Duration.ofSeconds(1))) {
      assertEquals("mounted", resolver.require("key").toCompletableFuture().join().reveal());
    }
    Files.write(directory.resolve("oversized"), new byte[1024 * 1024 + 1]);
    assertThrows(
        IllegalStateException.class,
        () -> new RefreshingFileSecretResolver(directory, Duration.ofSeconds(1)));
    Files.delete(directory.resolve("oversized"));
    Files.write(directory.resolve("invalid"), new byte[] {(byte) 0xC3, (byte) 0x28});
    assertThrows(
        java.io.UncheckedIOException.class,
        () -> new RefreshingFileSecretResolver(directory, Duration.ofSeconds(1)));
  }

  @Test
  void aRefreshFailureDoesNotKeepServingAnOldSecretAndCanRecover(@TempDir Path directory)
      throws Exception {
    Path file = directory.resolve("key");
    Files.writeString(file, "initial");
    try (var resolver = new RefreshingFileSecretResolver(directory, Duration.ofMillis(25))) {
      replace(file, new byte[] {(byte) 0xC3, (byte) 0x28});
      eventually(() -> resolver.resolve("key").toCompletableFuture().isCompletedExceptionally());
      replace(file, "recovered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      eventually(
          () -> {
            try {
              return resolver
                  .require("key")
                  .toCompletableFuture()
                  .join()
                  .reveal()
                  .equals("recovered");
            } catch (CompletionException stillRefreshing) {
              return false;
            }
          });
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new RefreshingFileSecretResolver(directory, Duration.ZERO));
  }
}
