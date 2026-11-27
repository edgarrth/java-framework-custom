package pe.axiz.test.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishResult;

class AsyncFakesTest {
  public interface StageApi {
    CompletionStage<String> get(String key);

    String name();

    void close();
  }

  public interface RedisFuture extends CompletionStage<String>, Future<String> {
    String getError();

    boolean await(long time, TimeUnit unit);
  }

  public interface RedisApi {
    RedisFuture get(String key);
  }

  public static class Protocol {
    boolean closed;

    public String get(String key) {
      if (key.equals("fail")) throw new IllegalArgumentException("remote error");
      return "value:" + key;
    }

    public String name() {
      return "memory";
    }

    public void close() {
      closed = true;
    }
  }

  @Test
  void nativeStageUsesTheSameProtocolAndFailsVisibly() {
    Protocol protocol = new Protocol();
    StageApi client = ImmediateAsyncFakes.adapt(StageApi.class, protocol);
    assertEquals("memory", client.name());
    assertEquals("value:one", client.get("one").toCompletableFuture().join());
    var error =
        assertThrows(
            CompletionException.class, () -> client.get("fail").toCompletableFuture().join());
    assertEquals("remote error", error.getCause().getMessage());
    assertTrue(client.equals(client));
    assertFalse(client.equals(null));
    assertEquals(System.identityHashCode(client), client.hashCode());
    assertTrue(client.toString().startsWith("ImmediateAsyncFake@"));
    client.close();
    assertTrue(protocol.closed);
  }

  @Test
  void redisFutureExposesCompletionFailureAndNormalStageChaining() throws Exception {
    RedisApi client = ImmediateAsyncFakes.adapt(RedisApi.class, new Protocol());
    RedisFuture success = client.get("one");
    assertTrue(success.await(1, TimeUnit.MILLISECONDS));
    Assertions.assertNull(success.getError());
    assertEquals("value:one!", success.thenApply(v -> v + "!").toCompletableFuture().get());
    assertTrue(success.equals(success));
    assertEquals(System.identityHashCode(success), success.hashCode());
    assertTrue(success.toString().startsWith("ImmediateAsyncFake@"));
    RedisFuture failed = client.get("fail");
    assertTrue(failed.await(1, TimeUnit.MILLISECONDS));
    assertTrue(failed.getError().contains("remote error"));
    assertThrows(ExecutionException.class, failed::get);
  }

  public interface BooleanApi {
    boolean hasNext();
  }

  @Test
  void inaccessibleFakeMethodsAreRejectedInsteadOfIgnoringTheProtocol() {
    var client = ImmediateAsyncFakes.adapt(BooleanApi.class, Collections.emptyIterator());
    assertThrows(IllegalArgumentException.class, client::hasNext);
  }

  @Test
  void publisherProbePreservesAcknowledgementAndEveryKindOfFailure() {
    var event =
        EventEnvelope.builder()
            .id("e")
            .source("urn:test")
            .type("type")
            .time(Instant.EPOCH)
            .subject(null)
            .dataContentType("text/plain")
            .data(new byte[0])
            .extensions(Map.of())
            .build();
    var acknowledged = new PublishResult("e", "physical", "receipt");
    var success =
        new AsyncPublisherProbe(
            (destination, envelope) -> CompletableFuture.completedFuture(acknowledged));
    assertSame(acknowledged, success.publish("logical", event));
    assertTrue(success.supports("logical"));
    RuntimeException runtime = new IllegalStateException("broker rejected");
    var failed =
        new AsyncPublisherProbe((destination, envelope) -> CompletableFuture.failedFuture(runtime));
    assertSame(
        runtime, assertThrows(RuntimeException.class, () -> failed.publish("logical", event)));
    Error fatal = new AssertionError("contract failed");
    var fatalProbe =
        new AsyncPublisherProbe((destination, envelope) -> CompletableFuture.failedFuture(fatal));
    assertSame(fatal, assertThrows(Error.class, () -> fatalProbe.publish("logical", event)));
    var checked =
        new AsyncPublisherProbe(
            (destination, envelope) -> CompletableFuture.failedFuture(new IOException("offline")));
    assertThrows(AssertionError.class, () -> checked.publish("logical", event));
    var timeout =
        new AsyncPublisherProbe(
            (destination, envelope) ->
                new CompletableFuture<>() {
                  @Override
                  public PublishResult get(long time, TimeUnit unit) throws TimeoutException {
                    throw new TimeoutException();
                  }
                });
    assertThrows(AssertionError.class, () -> timeout.publish("logical", event));
    var interrupted =
        new AsyncPublisherProbe(
            (destination, envelope) ->
                new CompletableFuture<>() {
                  @Override
                  public PublishResult get(long time, TimeUnit unit) throws InterruptedException {
                    throw new InterruptedException();
                  }
                });
    try {
      assertThrows(AssertionError.class, () -> interrupted.publish("logical", event));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
