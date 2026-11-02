package pe.axiz.adapter.audit.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditPublishException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsAsyncAuditPublisherTest {
  static final class Client implements SqsAsyncClient {
    final List<SendMessageRequest> requests = new ArrayList<>();
    CompletableFuture<SendMessageResponse> response = new CompletableFuture<>();
    boolean fail;
    boolean closed;

    public CompletableFuture<SendMessageResponse> sendMessage(SendMessageRequest request) {
      if (fail) throw SqsException.builder().message("down").build();
      requests.add(request);
      return response;
    }

    public String serviceName() {
      return "sqs";
    }

    public void close() {
      closed = true;
    }
  }

  @Test
  void preservesWireFormatAndWaitsForRealAcceptance() {
    Client client = new Client();
    var event = event("id");
    var pending =
        new SqsAsyncAuditPublisher(client, "https://local/a.fifo")
            .publish(event)
            .toCompletableFuture();
    assertFalse(pending.isDone());
    assertEquals(
        SqsAuditPublisher.request("https://local/a.fifo", true, event), client.requests.getFirst());
    assertEquals("account:none", client.requests.getFirst().messageGroupId());
    client.response.complete(SendMessageResponse.builder().messageId("id").build());
    assertNull(pending.join());
    assertFalse(client.closed);
    client.response = CompletableFuture.completedFuture(SendMessageResponse.builder().build());
    new SqsAsyncAuditPublisher(client, "https://local/a")
        .publish(event)
        .toCompletableFuture()
        .join();
    assertNull(client.requests.getLast().messageGroupId());
  }

  @Test
  void cancellationReachesExactSdkFutureAndFailuresAreTyped() {
    Client client = new Client();
    var adapter = new SqsAsyncAuditPublisher(client, "queue");
    assertTrue(adapter.publish(event("id")).toCompletableFuture().cancel(true));
    assertTrue(client.response.isCancelled());
    client.response = new CompletableFuture<>();
    var result = adapter.publish(event("id"));
    var failure = SqsException.builder().message("down").build();
    client.response.completeExceptionally(failure);
    var typed =
        assertInstanceOf(
            AuditPublishException.class,
            assertThrows(CompletionException.class, () -> result.toCompletableFuture().join())
                .getCause());
    assertSame(failure, typed.getCause());
    client.fail = true;
    assertInstanceOf(
        AuditPublishException.class,
        assertThrows(
                CompletionException.class,
                () -> adapter.publish(event("id")).toCompletableFuture().join())
            .getCause());
    client.fail = false;
    client.response = CompletableFuture.failedFuture(new CancellationException("cancelled"));
    assertThrows(
        CancellationException.class,
        () -> adapter.publish(event("id")).toCompletableFuture().join());
    client.response = CompletableFuture.failedFuture(new AssertionError("fatal"));
    assertInstanceOf(
        AssertionError.class,
        assertThrows(
                CompletionException.class,
                () -> adapter.publish(event("id")).toCompletableFuture().join())
            .getCause());
  }

  private static AuditEvent event(String id) {
    return new AuditEvent(
        id,
        Instant.EPOCH,
        "svc",
        "create",
        "account",
        null,
        AuditEvent.Actor.system(),
        AuditEvent.Outcome.SUCCESS,
        null,
        "corr",
        null,
        null,
        Map.of());
  }
}
