package pe.axiz.adapter.messaging.sns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

class SnsNativePublisherTest {
  private static final EventEnvelope EVENT =
      EventEnvelope.builder()
          .id("id")
          .source("urn:service")
          .type("event.v1")
          .time(Instant.EPOCH)
          .subject(null)
          .dataContentType("application/json")
          .data(new byte[0])
          .extensions(Map.of())
          .build();

  static final class Client implements SnsAsyncClient {
    CompletableFuture<PublishResponse> response = new CompletableFuture<>();
    boolean reject;
    PublishRequest sent;

    public CompletableFuture<PublishResponse> publish(PublishRequest request) {
      if (reject) throw SnsException.builder().message("unavailable").build();
      sent = request;
      return response;
    }

    public String serviceName() {
      return "sns";
    }

    public void close() {}
  }

  @Test
  void acceptanceAndCancellationFollowNativeOperationWithSharedWirePolicy() {
    var client = new Client();
    var publisher = new SnsAsyncEventPublisher(client);
    var result =
        publisher
            .publish("arn:aws:sns:us-east-1:000000000000:queue.fifo", EVENT)
            .toCompletableFuture();
    assertFalse(result.isDone());
    assertEquals("event.v1", client.sent.messageGroupId());
    assertEquals("id", client.sent.messageDeduplicationId());
    assertTrue(result.cancel(true));
    assertTrue(client.response.isCancelled());
    client.response =
        CompletableFuture.completedFuture(PublishResponse.builder().messageId("accepted").build());
    assertEquals(
        "accepted",
        publisher
            .publish("arn:aws:sns:us-east-1:000000000000:queue", EVENT)
            .toCompletableFuture()
            .join()
            .brokerMessageId());
  }

  @Test
  void
      synchronousAndAsynchronousSubmissionFailuresAreVisibleAndCancellationAndFatalErrorsArePreserved() {
    var client = new Client();
    var publisher = new SnsAsyncEventPublisher(client);
    client.reject = true;
    assertInstanceOf(
        PublishException.class,
        assertThrows(
                CompletionException.class,
                () ->
                    publisher
                        .publish("arn:aws:sns:us-east-1:000000000000:queue", EVENT)
                        .toCompletableFuture()
                        .join())
            .getCause());
    client.reject = false;
    var original = SnsException.builder().message("down").build();
    client.response = CompletableFuture.failedFuture(original);
    var failure =
        assertInstanceOf(
            PublishException.class,
            assertThrows(
                    CompletionException.class,
                    () ->
                        publisher
                            .publish("arn:aws:sns:us-east-1:000000000000:queue", EVENT)
                            .toCompletableFuture()
                            .join())
                .getCause());
    assertSame(original, failure.getCause());
    client.response = CompletableFuture.failedFuture(new CancellationException());
    assertThrows(
        CancellationException.class,
        () ->
            publisher
                .publish("arn:aws:sns:us-east-1:000000000000:queue", EVENT)
                .toCompletableFuture()
                .join());
    client.response = CompletableFuture.failedFuture(new AssertionError("fatal"));
    assertInstanceOf(
        AssertionError.class,
        assertThrows(
                CompletionException.class,
                () ->
                    publisher
                        .publish("arn:aws:sns:us-east-1:000000000000:queue", EVENT)
                        .toCompletableFuture()
                        .join())
            .getCause());
  }
}
