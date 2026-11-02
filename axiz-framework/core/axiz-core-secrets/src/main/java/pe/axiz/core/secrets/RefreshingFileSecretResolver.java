package pe.axiz.core.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.Stages;

/**
 * Mounted-secret snapshots loaded at startup and refreshed independently of requests on one owned
 * background worker. Request resolution performs no filesystem I/O. Refresh errors fail closed.
 * Limits: 1000 files, 1 MiB per file and 16 MiB per snapshot, with strict UTF-8 decoding.
 */
public final class RefreshingFileSecretResolver implements AsyncSecretResolver, AutoCloseable {
  private static final int MAX_FILE_BYTES = 1024 * 1024;
  private static final int MAX_SNAPSHOT_BYTES = 16 * MAX_FILE_BYTES;
  private final Path directory;
  private final ScheduledThreadPoolExecutor executor;
  private final ScheduledFuture<?> refreshTask;
  private final AtomicReference<Snapshot> current;
  private final AtomicBoolean closed = new AtomicBoolean();

  public RefreshingFileSecretResolver(Path directory, Duration interval) {
    this.directory = directory.toAbsolutePath().normalize();
    if (interval.toMillis() < 10)
      throw new IllegalArgumentException("Secret refresh interval must be at least 10ms");
    current = new AtomicReference<>(new Snapshot(readFiles(), null));
    executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("axiz-secret-files-", 0).factory());
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    refreshTask =
        executor.scheduleWithFixedDelay(
            this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletionStage<Optional<SecretValue>> resolve(String name) {
    return Stages.call(
        () -> {
          String file = FileSecretResolver.fileName(name);
          Snapshot snapshot = current.get();
          Throwable failure = snapshot.failure();
          if (failure != null) return CompletableFuture.failedFuture(failure);
          return CompletableFuture.completedFuture(
              Optional.ofNullable(snapshot.values().get(file)));
        });
  }

  private void refresh() {
    if (closed.get()) return;
    Snapshot before = current.get();
    Snapshot next;
    try {
      next = new Snapshot(readFiles(), null);
    } catch (RuntimeException | Error failure) {
      next = new Snapshot(Map.of(), failure);
    }
    if (!closed.get()) current.compareAndSet(before, next);
  }

  private Map<String, SecretValue> readFiles() {
    Map<String, SecretValue> values = new HashMap<>();
    int total = 0;
    int files = 0;
    try (var entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name =
            Objects.requireNonNull(entry.getFileName(), "Secret entry has no filename").toString();
        try {
          FileSecretResolver.fileName(name);
        } catch (IllegalArgumentException invalid) {
          continue; // Kubernetes metadata names are not logical secret names.
        }
        try {
          if (!Files.readAttributes(entry, BasicFileAttributes.class).isRegularFile()) continue;
          if (++files > CachingSecretResolver.MAX_CACHED_SECRETS)
            throw new IllegalStateException("Mounted secret snapshot has too many files");
          byte[] bytes;
          try (var input = Files.newInputStream(entry)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
          }
          total = Math.addExact(total, bytes.length);
          if (bytes.length > MAX_FILE_BYTES || total > MAX_SNAPSHOT_BYTES)
            throw new IllegalStateException("Mounted secret snapshot exceeds byte limits");
          String value =
              StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().strip();
          if (!value.isEmpty()) values.put(name, SecretValue.of(value));
        } catch (NoSuchFileException removed) {
          // A file removed during an atomic rotation is absent in this snapshot.
        }
      }
      return Map.copyOf(values);
    } catch (NoSuchFileException missingDirectory) {
      return Map.of();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot refresh mounted secrets", failure);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      current.set(
          new Snapshot(Map.of(), new IllegalStateException("Secret snapshot resolver is closed")));
      refreshTask.cancel(true);
      executor.shutdownNow();
    }
  }

  private record Snapshot(Map<String, SecretValue> values, @Nullable Throwable failure) {
    private Snapshot {
      values = Map.copyOf(values);
    }
  }
}
