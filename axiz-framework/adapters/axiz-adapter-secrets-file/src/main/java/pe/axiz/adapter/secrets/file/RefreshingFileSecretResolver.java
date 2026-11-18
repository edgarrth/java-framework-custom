package pe.axiz.adapter.secrets.file;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.secrets.AsyncSecretResolver;
import pe.axiz.core.secrets.SecretValue;

/**
 * Mounted-secret snapshots loaded at startup and refreshed independently of requests on one owned
 * background worker. Request resolution performs no filesystem I/O. Refresh errors fail closed.
 * Limits: 1000 files, 1 MiB per file and 16 MiB per snapshot, with strict UTF-8 decoding.
 */
public final class RefreshingFileSecretResolver implements AsyncSecretResolver, AutoCloseable {
  private static final int MAX_FILES = 1000;
  private static final int MAX_FILE_BYTES = SecretFileContents.MAX_BYTES;
  private static final int MAX_SNAPSHOT_BYTES = 16 * MAX_FILE_BYTES;
  private final Path directory;
  private final ScheduledThreadPoolExecutor executor;
  private final ScheduledFuture<?> refreshTask;
  private final AtomicReference<Snapshot> current;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object snapshotLock = new Object();

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
          synchronized (snapshotLock) {
            Snapshot snapshot = current.get();
            Throwable failure = snapshot.failure();
            if (failure != null) return CompletableFuture.failedFuture(failure);
            return CompletableFuture.completedFuture(
                Optional.ofNullable(snapshot.values().get(file))
                    .map(value -> value.use(SecretValue::of)));
          }
        });
  }

  private void refresh() {
    if (closed.get()) return;
    Snapshot next;
    try {
      next = new Snapshot(readFiles(), null);
    } catch (RuntimeException | Error failure) {
      next = new Snapshot(Map.of(), failure);
    }
    Snapshot retired;
    synchronized (snapshotLock) {
      retired = closed.get() ? next : current.getAndSet(next);
    }
    retired.close();
  }

  private Map<String, SecretValue> readFiles() {
    Map<String, SecretValue> values = new HashMap<>();
    int total = 0;
    int files = 0;
    boolean transferred = false;
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
          if (++files > MAX_FILES)
            throw new IllegalStateException("Mounted secret snapshot has too many files");
          var contents = SecretFileContents.read(directory, entry);
          total = Math.addExact(total, contents.bytes());
          if (total > MAX_SNAPSHOT_BYTES) {
            contents.value().ifPresent(SecretValue::close);
            throw new IllegalStateException("Mounted secret snapshot exceeds byte limits");
          }
          contents
              .value()
              .ifPresent(
                  value -> {
                    SecretValue previous = values.put(name, value);
                    if (previous != null) previous.close();
                  });
        } catch (NoSuchFileException removed) {
          // A file removed during an atomic rotation is absent in this snapshot.
        }
      }
      Map<String, SecretValue> result = Map.copyOf(values);
      transferred = true;
      return result;
    } catch (NoSuchFileException missingDirectory) {
      return Map.of();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot refresh mounted secrets", failure);
    } finally {
      if (!transferred) values.values().forEach(SecretValue::close);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      Snapshot retired;
      synchronized (snapshotLock) {
        retired =
            current.getAndSet(
                new Snapshot(
                    Map.of(), new IllegalStateException("Secret snapshot resolver is closed")));
      }
      retired.close();
      refreshTask.cancel(true);
      executor.shutdownNow();
    }
  }

  private record Snapshot(Map<String, SecretValue> values, @Nullable Throwable failure)
      implements AutoCloseable {
    private Snapshot {
      values = Map.copyOf(values);
    }

    @Override
    public void close() {
      values.values().forEach(SecretValue::close);
    }
  }
}
