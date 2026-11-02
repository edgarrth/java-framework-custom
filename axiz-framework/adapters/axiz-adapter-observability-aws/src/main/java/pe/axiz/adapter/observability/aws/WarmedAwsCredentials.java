package pe.axiz.adapter.observability.aws;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Credentials are resolved at startup and refreshed in the background; request threads only read an
 * immutable snapshot.
 */
public final class WarmedAwsCredentials implements AwsCredentialsProvider, SdkAutoCloseable {
  private final AwsCredentialsProvider source;
  private final Clock clock;
  private final AtomicReference<AwsCredentials> current;
  private final ScheduledExecutorService refresh;
  private final java.util.concurrent.ScheduledFuture<?> refreshTask;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();

  public WarmedAwsCredentials(AwsCredentialsProvider source, Clock clock, Duration interval) {
    this.source = Objects.requireNonNull(source);
    this.clock = Objects.requireNonNull(clock);
    if (!interval.isPositive() || interval.toMillis() < 1)
      throw new IllegalArgumentException("refresh interval must be positive");
    AwsCredentials initial;
    try {
      initial = source.resolveCredentials();
    } catch (RuntimeException | Error failure) {
      if (source instanceof SdkAutoCloseable closeable) {
        try {
          closeable.close();
        } catch (RuntimeException | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
    this.current = new AtomicReference<>(initial);
    this.refresh =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("axiz-aws-credential-refresh").factory());
    refreshTask =
        refresh.scheduleWithFixedDelay(
            this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  void refresh() {
    if (closed.get()) return;
    try {
      current.set(source.resolveCredentials());
    } catch (RuntimeException failure) {
      LoggerFactory.getLogger(WarmedAwsCredentials.class)
          .warn("AWS credential refresh failed; expired snapshots fail closed", failure);
    }
  }

  @Override
  public AwsCredentials resolveCredentials() {
    if (closed.get()) throw SdkClientException.create("AWS credential snapshot provider is closed");
    AwsCredentials credentials = current.get();
    if (credentials
        .expirationTime()
        .filter(expiry -> !expiry.isAfter(clock.instant().plusSeconds(10)))
        .isPresent()) {
      throw SdkClientException.create(
          "AWS credentials expired or near expiry; background refresh has not supplied a valid snapshot");
    }
    return credentials;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    refreshTask.cancel(true);
    refresh.shutdownNow();
    if (source instanceof SdkAutoCloseable closeable) closeable.close();
  }
}
