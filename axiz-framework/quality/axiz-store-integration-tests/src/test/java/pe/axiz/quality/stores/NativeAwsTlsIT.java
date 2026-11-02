package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/** A real cold TLS handshake, strict trust/hostname checks and reconnection on watched threads. */
@ExtendWith(BlockHoundExtension.class)
class NativeAwsTlsIT {
  @TempDir Path temporary;

  @Test
  @Timeout(90)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void tlsUsesStartupTrustMaterialEvenWhenItsFileIsGoneAndRejectsUntrustedOrWrongHosts()
      throws Exception {
    pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap.initialize();
    Path store = temporary.resolve("server.p12");
    Path log = temporary.resolve("keytool.log");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "local-test",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "3",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "changeit")
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, keytool.exitValue(), () -> "Test certificate generation failed; see " + log);
    } finally {
      if (keytool.isAlive()) keytool.destroyForcibly();
    }
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "changeit".toCharArray());
    }
    var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    managers.init(keys, "changeit".toCharArray());
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(managers.getKeyManagers(), null, null);
    var server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(ssl));
    var properties =
        Map.of(
            "javax.net.ssl.trustStore",
            store.toString(),
            "javax.net.ssl.trustStorePassword",
            "changeit",
            "javax.net.ssl.trustStoreType",
            "PKCS12");
    var previous = new java.util.HashMap<String, String>();
    properties.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
    var lifecycle = new NettyLifecycleProbe();
    var observedHeaders = new java.util.concurrent.ConcurrentLinkedQueue<Map<String, String>>();
    String traceparent = "00-11111111111111111111111111111111-2222222222222222-01";
    var observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new io.micrometer.observation.ObservationHandler<
                io.micrometer.observation.transport.SenderContext<Map<String, String>>>() {
              public boolean supportsContext(
                  io.micrometer.observation.Observation.Context context) {
                return context instanceof io.micrometer.observation.transport.SenderContext<?>;
              }

              public void onStart(
                  io.micrometer.observation.transport.SenderContext<Map<String, String>> context) {
                assertEquals(io.micrometer.observation.transport.Kind.CLIENT, context.getKind());
                context.getSetter().set(context.getCarrier(), "traceparent", traceparent);
              }
            });
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor();
        var resources =
            new AsyncAwsClients(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")),
                Clock.systemUTC(),
                Duration.ofMinutes(1))) {
      server.setExecutor(handlers);
      server.createContext(
          "/",
          exchange -> {
            observedHeaders.add(
                Map.of(
                    "traceparent",
                    String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")),
                    "authorization",
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"))));
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("Content-Length", "4");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
          });
      server.start();
      String endpoint = "https://localhost:" + server.getAddress().getPort();
      try (var untrusted = client(resources, endpoint, observations)) {
        properties.forEach(System::setProperty);
        try (var trusted = client(resources, endpoint, observations);
            var wrongHost =
                client(
                    resources,
                    "https://127.0.0.1:" + server.getAddress().getPort(),
                    observations)) {
          Files.delete(store);
          for (int index = 0; index < 2; index++) {
            StepVerifier.create(
                    Mono.fromCompletionStage(
                            () -> trusted.headObject(b -> b.bucket("test").key("file")))
                        .subscribeOn(Schedulers.parallel()))
                .assertNext(response -> assertEquals("text/plain", response.contentType()))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
          }
          assertFalse(Files.exists(store));
          assertEquals(2, observedHeaders.size());
          for (var headers : observedHeaders) {
            assertEquals(traceparent, headers.get("traceparent"));
            assertTrue(
                headers.get("authorization").contains("traceparent"),
                "propagation must be included in SigV4 signed headers");
          }
          StepVerifier.create(
                  Mono.fromCompletionStage(
                          () -> wrongHost.headObject(b -> b.bucket("test").key("file")))
                      .subscribeOn(Schedulers.parallel()))
              .expectErrorSatisfies(NativeAwsTlsIT::assertHandshakeFailure)
              .verify(Duration.ofSeconds(10));
        } finally {
          properties
              .keySet()
              .forEach(
                  key -> {
                    String value = previous.get(key);
                    if (value == null) System.clearProperty(key);
                    else System.setProperty(key, value);
                  });
        }
        StepVerifier.create(
                Mono.fromCompletionStage(
                        () -> untrusted.headObject(b -> b.bucket("test").key("file")))
                    .subscribeOn(Schedulers.parallel()))
            .expectErrorSatisfies(NativeAwsTlsIT::assertHandshakeFailure)
            .verify(Duration.ofSeconds(10));
      } finally {
        server.stop(0);
      }
    } finally {
      lifecycle.close();
    }
    assertTrue(lifecycle.errors.isEmpty(), () -> "SDK lifecycle errors: " + lifecycle.errors);
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        () -> "Original blocking sites: " + BlockHoundExtension.detectedBlockingCalls());
  }

  private static S3AsyncClient client(
      AsyncAwsClients resources, String endpoint, ObservationRegistry observations) {
    return resources.configured(
        S3AsyncClient.builder().forcePathStyle(true),
        observations,
        key ->
            switch (key) {
              case "axiz.aws.region" -> "us-east-1";
              case "axiz.aws.endpoint" -> endpoint;
              case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
              default -> null;
            },
        "s3");
  }

  private static void assertHandshakeFailure(Throwable failure) {
    assertInstanceOf(SdkClientException.class, failure);
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof javax.net.ssl.SSLHandshakeException) return;
    }
    fail("Expected certificate/hostname rejection, got " + failure);
  }
}
