package pe.axiz.adapter.aws.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

class WarmedAwsCredentialsTest {
  @Test
  void oneMillisecondIsTheMinimumAcceptedRefreshInterval() {
    var source = new Source();
    try (var warmed = new WarmedAwsCredentials(source.resource(), CLOCK, Duration.ofMillis(1))) {
      assertEquals("initial", warmed.resolveCredentials().accessKeyId());
    }
    assertEquals(1, source.closes);
  }

  static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  static final class Source implements AwsCredentialsProvider, SdkAutoCloseable {
    AwsCredentials next = credentials("initial", NOW.plusSeconds(100));
    final AtomicInteger calls = new AtomicInteger();
    int closes;
    boolean down;
    boolean failClose;

    @Override
    public AwsCredentials resolveCredentials() {
      calls.incrementAndGet();
      if (down) throw new IllegalStateException("credential endpoint unavailable");
      return next;
    }

    AwsCredentialSource resource() {
      return AwsCredentialSource.owned(this, this::close);
    }

    @Override
    public void close() {
      closes++;
      if (failClose) throw new IllegalStateException("close failed");
    }
  }

  static AwsCredentials credentials(String id, Instant expiration) {
    return AwsSessionCredentials.builder()
        .accessKeyId(id)
        .secretAccessKey("fixture-secret")
        .sessionToken("fixture-session")
        .expirationTime(expiration)
        .build();
  }

  @Test
  void requestThreadsOnlyReadSnapshotAndBackgroundRefreshFailureDoesNotCallSourceAgain() {
    Source source = new Source();
    try (var warmed = new WarmedAwsCredentials(source.resource(), CLOCK, Duration.ofDays(1))) {
      assertEquals(1, source.calls.get());
      for (int i = 0; i < 100; i++) assertSame(source.next, warmed.resolveCredentials());
      assertEquals(1, source.calls.get());
      source.next = credentials("refreshed", NOW.plusSeconds(200));
      CompletableFuture.runAsync(warmed::refresh).join();
      assertSame(source.next, warmed.resolveCredentials());
      source.down = true;
      warmed.refresh();
      assertEquals("refreshed", warmed.resolveCredentials().accessKeyId());
      assertEquals(3, source.calls.get());
    }
    assertEquals(1, source.closes);
  }

  @Test
  void nearExpirationFailsClosedAndInitialFailureClosesOwnedSource() {
    Source source = new Source();
    source.next = credentials("expired", NOW.plusSeconds(10));
    try (var warmed = new WarmedAwsCredentials(source.resource(), CLOCK, Duration.ofDays(1))) {
      assertThrows(SdkClientException.class, warmed::resolveCredentials);
      source.next = credentials("new", NOW.plusSeconds(60));
      warmed.refresh();
      assertEquals("new", warmed.resolveCredentials().accessKeyId());
    }
    Source down = new Source();
    down.down = true;
    down.failClose = true;
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> new WarmedAwsCredentials(down.resource(), CLOCK, Duration.ofDays(1)));
    assertEquals(1, down.closes);
    assertEquals(1, failure.getSuppressed().length);
    assertThrows(
        IllegalArgumentException.class,
        () -> new WarmedAwsCredentials(new Source().resource(), CLOCK, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WarmedAwsCredentials(new Source().resource(), CLOCK, Duration.ofNanos(1)));
    AwsCredentialsProvider unavailable =
        () -> {
          throw new IllegalStateException("offline");
        };
    assertThrows(
        IllegalStateException.class,
        () ->
            new WarmedAwsCredentials(
                AwsCredentialSource.borrowed(unavailable), CLOCK, Duration.ofDays(1)));
  }

  @Test
  void closingOneNativeClientCannotCloseSharedCredentialRefresh() {
    Source source = new Source();
    var warmed = new WarmedAwsCredentials(source.resource(), CLOCK, Duration.ofDays(1));
    warmed.close();
    warmed.close();
    warmed.refresh();
    assertEquals(1, source.calls.get());
    assertEquals(1, source.closes);
    assertThrows(SdkClientException.class, warmed::resolveCredentials);
    var basic = StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture", "fixture"));
    try (var withoutExpiry =
        new WarmedAwsCredentials(AwsCredentialSource.borrowed(basic), CLOCK, Duration.ofDays(1))) {
      assertEquals("fixture", withoutExpiry.resolveCredentials().accessKeyId());
    }
  }
}
