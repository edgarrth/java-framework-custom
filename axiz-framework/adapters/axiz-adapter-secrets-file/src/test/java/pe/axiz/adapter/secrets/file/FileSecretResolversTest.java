package pe.axiz.adapter.secrets.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.secrets.CompositeSecretResolver;
import pe.axiz.core.secrets.SecretNotFoundException;

class FileSecretResolversTest {
  @Test
  void environmentAndFileResolvers(@TempDir Path dir) throws Exception {
    EnvironmentSecretResolver env =
        new EnvironmentSecretResolver(Map.of("PAYMENTS_DB_PASSWORD", "x", "EMPTY", ""));
    assertEquals("x", env.require("payments/db-password").reveal());
    assertTrue(env.resolve("empty").isEmpty());
    Files.writeString(dir.resolve("payments.api-key"), "key-1\n");
    FileSecretResolver files = new FileSecretResolver(dir);
    assertEquals("key-1", files.require("payments/api-key").reveal());
    Files.writeString(dir.resolve("payments.db.password"), "field-override");
    assertEquals("field-override", files.require("payments/db#password").reveal());
    assertTrue(files.resolve("missing").isEmpty());
    assertThrows(IllegalArgumentException.class, () -> files.resolve("../etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> files.resolve("a b"));
    CompositeSecretResolver composite = new CompositeSecretResolver(List.of(env, files));
    assertEquals("key-1", composite.require("payments/api-key").reveal());
    SecretNotFoundException e =
        assertThrows(SecretNotFoundException.class, () -> composite.require("nope"));
    assertFalse(e.getMessage().contains("key-1"));
    assertTrue(EnvironmentSecretResolver.system() != null);
    var async = new EnvironmentAsyncSecretResolver(Map.of("PAYMENTS_DB_PASSWORD", "x"));
    assertEquals("x", async.require("payments/db-password").toCompletableFuture().join().reveal());
    assertTrue(async.resolve("missing").toCompletableFuture().join().isEmpty());
    assertTrue(EnvironmentAsyncSecretResolver.system() != null);
  }

  @Test
  void synchronousReadsAreBoundedStrictAndConfineSymlinksToTheMountedDirectory(@TempDir Path root)
      throws Exception {
    Path directory = Files.createDirectory(root.resolve("mounted"));
    var files = new FileSecretResolver(directory);
    Files.writeString(directory.resolve("key"), "\u2003 00123456 \n");
    try (var value = files.require("key")) {
      assertEquals("00123456", value.reveal());
    }
    Files.write(directory.resolve("huge"), new byte[1024 * 1024 + 1]);
    assertThrows(IllegalStateException.class, () -> files.resolve("huge"));
    Files.write(directory.resolve("invalid"), new byte[] {(byte) 0xc3, 0x28});
    assertThrows(UncheckedIOException.class, () -> files.resolve("invalid"));
    Files.writeString(root.resolve("outside"), "must-not-read");
    Files.createSymbolicLink(directory.resolve("escape"), root.resolve("outside"));
    assertThrows(IllegalStateException.class, () -> files.resolve("escape"));
    Path data = Files.createDirectory(directory.resolve("..data"));
    Files.writeString(data.resolve("internal"), "allowed");
    Files.createSymbolicLink(directory.resolve("internal"), Path.of("..data/internal"));
    try (var value = files.require("internal")) {
      assertEquals("allowed", value.reveal());
    }
  }
}
