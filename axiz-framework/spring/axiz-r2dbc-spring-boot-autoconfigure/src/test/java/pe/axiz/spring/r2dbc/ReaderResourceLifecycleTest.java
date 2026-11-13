package pe.axiz.spring.r2dbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.Closeable;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import pe.axiz.test.support.r2dbc.FakeR2dbc;
import reactor.core.publisher.Mono;

class ReaderResourceLifecycleTest {
  @Test
  void credentialsCloseAfterPoolFailureAndCannotReplaceTheOriginalError() {
    var pool = new IllegalArgumentException("pool close");
    var identity = new IllegalStateException("identity close");
    var closed = new AtomicInteger();
    var reader =
        new ReaderDatabase(
            new ClosingFactory(Mono.error(pool)),
            () -> {
              closed.incrementAndGet();
              throw identity;
            });
    assertSame(pool, assertThrows(IllegalArgumentException.class, reader::destroy));
    assertEquals(1, closed.get());
    assertEquals(1, pool.getSuppressed().length);
    assertSame(identity, pool.getSuppressed()[0].getCause());
  }

  @Test
  void interruptedCredentialCleanupRestoresTheInterruptFlag() {
    var interruption = new InterruptedException("credential close");
    var reader =
        new ReaderDatabase(
            new ClosingFactory(Mono.empty()),
            () -> {
              throw interruption;
            });
    try {
      var error = assertThrows(IllegalStateException.class, reader::destroy);
      assertSame(interruption, error.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private record ClosingFactory(Mono<Void> closed) implements ConnectionFactory, Closeable {
    @Override
    public ConnectionFactoryMetadata getMetadata() {
      return new FakeR2dbc().getMetadata();
    }

    @Override
    public Publisher<? extends Connection> create() {
      return Mono.error(new AssertionError("No connection expected"));
    }

    @Override
    public Publisher<Void> close() {
      return closed;
    }
  }
}
