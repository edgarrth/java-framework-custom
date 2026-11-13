package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** First real native request on the adapter's own minimal classpath, without Spring or Reactor. */
class StandaloneAsyncAwsTest {
  @Test
  @Timeout(20)
  void nativeDnsAndTransportDoNotDependOnAnotherCapabilityProvidingTheirRuntimeClasses()
      throws Exception {
    var arrived = new CountDownLatch(1);
    var response = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(handlers);
      server.createContext(
          "/",
          exchange -> {
            arrived.countDown();
            try {
              if (!response.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Test response timed out");
              byte[] body =
                  "{\"Item\":{\"pk\":{\"S\":\"found\"}}}".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
            }
          });
      server.start();
      try (var resources =
              new AsyncAwsClients(
                  AwsCredentialSource.borrowed(
                      StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))),
                  Clock.systemUTC(),
                  Duration.ofMinutes(1),
                  AsyncAwsTransportSettings.defaults());
          var client =
              resources.configured(
                  DynamoDbAsyncClient.builder(),
                  key ->
                      switch (key) {
                        case "axiz.aws.region" -> "us-east-1";
                        case "axiz.config.environment" -> "test";
                        case "axiz.transport.allow-plain-http" -> "true";
                        case "axiz.aws.endpoint" ->
                            "http://localhost:" + server.getAddress().getPort();
                        default -> null;
                      },
                  "dynamodb")) {
        var observed = new CountDownLatch(1);
        var released = new java.util.concurrent.atomic.AtomicInteger();
        resources.observeCompletions(
            pool -> {
              assertEquals(0, pool.getActiveCount());
              assertEquals(0, pool.getQueueSize());
              return new CompletionObserver() {
                public void rejected() {}

                public void completed(long nanos) {
                  observed.countDown();
                }

                public void close() {
                  released.incrementAndGet();
                }
              };
            });
        var result =
            client.getItem(
                b ->
                    b.tableName("test").key(Map.of("pk", AttributeValue.builder().s("x").build())));
        assertTrue(arrived.await(5, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        response.countDown();
        assertEquals("found", result.get(5, TimeUnit.SECONDS).item().get("pk").s());
        assertTrue(observed.await(5, TimeUnit.SECONDS));
        resources.observeCompletions(
            pool ->
                new CompletionObserver() {
                  public void rejected() {}

                  public void completed(long nanos) {}
                });
        assertEquals(1, released.get());
      } finally {
        response.countDown();
        server.stop(0);
      }
    }
  }
}
