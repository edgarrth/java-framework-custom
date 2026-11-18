package pe.axiz.adapter.secrets.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.secrets.SecretValue;

class SecretSnapshotLimitsTest {
  @Test
  void emptyAndWhitespaceFilesAreAbsentAndTheExactByteLimitIsAccepted(@TempDir Path directory)
      throws Exception {
    var files = new FileSecretResolver(directory);
    Files.writeString(directory.resolve("empty"), "");
    Files.writeString(directory.resolve("blank"), " \t\u2003\n");
    assertTrue(files.resolve("empty").isEmpty());
    assertTrue(files.resolve("blank").isEmpty());
    Files.writeString(directory.resolve("exact"), "x".repeat(1024 * 1024));
    try (var value = files.require("exact")) {
      assertEquals(1024 * 1024, value.revealChars().length);
    }
    try (var snapshot = new RefreshingFileSecretResolver(directory, Duration.ofHours(1))) {
      assertTrue(snapshot.resolve("empty").toCompletableFuture().join().isEmpty());
      assertTrue(snapshot.resolve("blank").toCompletableFuture().join().isEmpty());
    }
  }

  @Test
  void fileCountIsBoundedAtOneThousand(@TempDir Path directory) throws Exception {
    for (int i = 0; i < 1000; i++) Files.writeString(directory.resolve("key-" + i), "");
    try (var accepted = new RefreshingFileSecretResolver(directory, Duration.ofHours(1))) {
      assertTrue(accepted.resolve("key-999").toCompletableFuture().join().isEmpty());
    }
    Files.writeString(directory.resolve("extra"), "");
    assertThrows(
        IllegalStateException.class,
        () -> new RefreshingFileSecretResolver(directory, Duration.ofHours(1)));
  }

  @Test
  void aggregateByteBudgetCannotBeBypassedWithIndividuallyValidFiles(@TempDir Path directory)
      throws Exception {
    byte[] bytes = new byte[1024 * 1024];
    Arrays.fill(bytes, (byte) ' ');
    bytes[0] = 'x';
    for (int i = 0; i < 16; i++) Files.write(directory.resolve("key-" + i), bytes);
    try (var accepted = new RefreshingFileSecretResolver(directory, Duration.ofHours(1));
        var value = accepted.require("key-15").toCompletableFuture().join()) {
      assertEquals("x", value.reveal());
    }
    Files.writeString(directory.resolve("extra"), "x");
    assertThrows(
        IllegalStateException.class,
        () -> new RefreshingFileSecretResolver(directory, Duration.ofHours(1)));
  }

  /**
   * Inspect owned secrets: public resolution intentionally returns copies, which must survive
   * rotation.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, SecretValue> ownedValues(RefreshingFileSecretResolver resolver)
      throws Exception {
    var field = RefreshingFileSecretResolver.class.getDeclaredField("current");
    field.setAccessible(true);
    var snapshot = ((AtomicReference<?>) field.get(resolver)).get();
    var values = snapshot.getClass().getDeclaredMethod("values");
    values.setAccessible(true);
    return (Map<String, SecretValue>) values.invoke(snapshot);
  }

  @Test
  void rotationAndCloseEraseOwnedSecretsWithoutDestroyingBorrowersCopies(@TempDir Path directory)
      throws Exception {
    Files.writeString(directory.resolve("key"), "original");
    try (var resolver = new RefreshingFileSecretResolver(directory, Duration.ofHours(1));
        var borrowed = resolver.require("key").toCompletableFuture().join()) {
      var retired = ownedValues(resolver).get("key");
      Files.writeString(directory.resolve("key"), "rotated");
      var refresh = RefreshingFileSecretResolver.class.getDeclaredMethod("refresh");
      refresh.setAccessible(true);
      refresh.invoke(resolver);
      assertThrows(IllegalStateException.class, retired::reveal);
      assertEquals("original", borrowed.reveal());
      var current = ownedValues(resolver).get("key");
      assertEquals("rotated", current.reveal());
      resolver.close();
      resolver.close();
      assertThrows(IllegalStateException.class, current::reveal);
      assertEquals("original", borrowed.reveal());
    }
  }
}
