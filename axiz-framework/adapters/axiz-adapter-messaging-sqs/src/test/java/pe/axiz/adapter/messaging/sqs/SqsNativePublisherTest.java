package pe.axiz.adapter.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.PublishException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsNativePublisherTest {
  private static final EventEnvelope EVENT =
      new EventEnvelope(
          "id",
          "urn:service",
          "event.v1",
          Instant.EPOCH,
          null,
          "application/json",
          new byte[0],
          Map.of());

  static final class Client implements SqsAsyncClient {
    CompletableFuture<SendMessageResponse> response = new CompletableFuture<>();
    boolean reject;
    SendMessageRequest sent;

    public CompletableFuture<SendMessageResponse> sendMessage(SendMessageRequest request) {
      if (reject) throw SqsException.builder().message("unavailable").build();
      sent = request;
      return response;
    }

    public String serviceName() {
      return "sqs";
    }

    public void close() {}
  }

  @Test
  void destinationMatchingSupportsAwsAndEmulatorsAndRejectsMalformedAddresses() {
    for (String address :
        List.of(
            "https://sqs.us-east-1.amazonaws.com/123/q",
            "http://sqs.local/q",
            "http://127.0.0.1:4566/000000000000/queue.fifo",
            "HTTPS://SQS.local/q")) assertTrue(SqsEventPublisher.isDestination(address), address);
    for (String address :
        List.of(
            "kafka:q",
            "arn:aws:sns:x:1:q",
            "https://example.com/queue",
            "http:///q",
            "not a URI",
            "ftp://sqs.local/q",
            "http://user:secret@sqs.local/q",
            "http://sqs.local/q?key=secret",
            "https://sqs.local/q#fragment"))
      assertFalse(SqsEventPublisher.isDestination(address), address);
  }

  @Test
  void acceptanceAndCancellationFollowNativeOperationWithSharedWirePolicy() {
    var client = new Client();
    var publisher = new SqsAsyncEventPublisher(client);
    var result =
        publisher
            .publish("http://127.0.0.1:4566/000000000000/queue.fifo", EVENT)
            .toCompletableFuture();
    assertFalse(result.isDone());
    assertEquals("event.v1", client.sent.messageGroupId());
    assertEquals("id", client.sent.messageDeduplicationId());
    assertTrue(result.cancel(true));
    assertTrue(client.response.isCancelled());
    client.response =
        CompletableFuture.completedFuture(
            SendMessageResponse.builder().messageId("accepted").build());
    assertEquals(
        "accepted",
        publisher
            .publish("http://sqs.local/q", EVENT)
            .toCompletableFuture()
            .join()
            .brokerMessageId());
  }

  @Test
  void
      synchronousAndAsynchronousSubmissionFailuresAreVisibleAndCancellationAndFatalErrorsArePreserved() {
    var client = new Client();
    var publisher = new SqsAsyncEventPublisher(client);
    client.reject = true;
    assertInstanceOf(
        PublishException.class,
        assertThrows(
                CompletionException.class,
                () -> publisher.publish("http://sqs.local/q", EVENT).toCompletableFuture().join())
            .getCause());
    client.reject = false;
    var original = SqsException.builder().message("down").build();
    client.response = CompletableFuture.failedFuture(original);
    var failure =
        assertInstanceOf(
            PublishException.class,
            assertThrows(
                    CompletionException.class,
                    () ->
                        publisher.publish("http://sqs.local/q", EVENT).toCompletableFuture().join())
                .getCause());
    assertSame(original, failure.getCause());
    client.response = CompletableFuture.failedFuture(new CancellationException());
    assertThrows(
        CancellationException.class,
        () -> publisher.publish("http://sqs.local/q", EVENT).toCompletableFuture().join());
    client.response = CompletableFuture.failedFuture(new AssertionError("fatal"));
    assertInstanceOf(
        AssertionError.class,
        assertThrows(
                CompletionException.class,
                () -> publisher.publish("http://sqs.local/q", EVENT).toCompletableFuture().join())
            .getCause());
  }
}
