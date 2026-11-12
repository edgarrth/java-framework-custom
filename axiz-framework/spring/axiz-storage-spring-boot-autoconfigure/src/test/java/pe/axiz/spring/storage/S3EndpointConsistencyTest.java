package pe.axiz.spring.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3EndpointConsistencyTest {
  @Test
  void imperativeClientAndPresignerUseTheSameEffectiveEndpointAndPathStyle() throws Exception {
    verify(false);
  }

  @Test
  void reactiveClientAndPresignerUseTheSameEffectiveEndpointAndPathStyle() throws Exception {
    verify(true);
  }

  private static void verify(boolean reactive) throws Exception {
    var received = new LinkedBlockingQueue<String>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.add(
              exchange.getRequestHeaders().getFirst("Host") + exchange.getRequestURI().getPath());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      String host = "http://localhost:" + server.getAddress().getPort();
      for (String specific : new String[] {null, "", " ", host + "/specific"}) {
        URI expected =
            URI.create(specific == null || specific.isBlank() ? host + "/global" : specific);
        var configurations =
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class, AxizStorageS3AutoConfiguration.class);
        String[] settings = {
          "axiz.aws.region=us-east-1",
          "axiz.aws.endpoint=" + host + "/global",
          "axiz.storage.bucket=fixture-bucket"
        };
        AwsCredentialsProvider credentials = () -> AwsBasicCredentials.create("fixture", "fixture");
        if (reactive) {
          new ReactiveWebApplicationContextRunner()
              .withConfiguration(configurations)
              .withPropertyValues(settings)
              .withBean(AwsCredentialsProvider.class, () -> credentials)
              .withInitializer(
                  ctx -> {
                    if (specific != null)
                      ctx.getEnvironment()
                          .getPropertySources()
                          .addFirst(
                              new MapPropertySource(
                                  "specific", Map.of("axiz.aws.s3.endpoint", specific)));
                  })
              .run(
                  ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var client = ctx.getBean(S3AsyncClient.class);
                    assertEquals(
                        expected,
                        client.serviceClientConfiguration().endpointOverride().orElseThrow());
                    client.headObject(request()).get(5, TimeUnit.SECONDS);
                    verifyPresigner(ctx.getBean(S3Presigner.class), expected, received);
                  });
        } else {
          new ApplicationContextRunner()
              .withConfiguration(configurations)
              .withPropertyValues(settings)
              .withBean(AwsCredentialsProvider.class, () -> credentials)
              .withInitializer(
                  ctx -> {
                    if (specific != null)
                      ctx.getEnvironment()
                          .getPropertySources()
                          .addFirst(
                              new MapPropertySource(
                                  "specific", Map.of("axiz.aws.s3.endpoint", specific)));
                  })
              .run(
                  ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var client = ctx.getBean(S3Client.class);
                    assertEquals(
                        expected,
                        client.serviceClientConfiguration().endpointOverride().orElseThrow());
                    client.headObject(request());
                    verifyPresigner(ctx.getBean(S3Presigner.class), expected, received);
                  });
        }
      }
    } finally {
      server.stop(0);
    }
  }

  private static HeadObjectRequest request() {
    return HeadObjectRequest.builder()
        .bucket("fixture-bucket")
        .key("key")
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(4)))
        .build();
  }

  private static void verifyPresigner(
      S3Presigner presigner, URI endpoint, LinkedBlockingQueue<String> received) throws Exception {
    var url =
        presigner
            .presignGetObject(
                p ->
                    p.signatureDuration(Duration.ofMinutes(1))
                        .getObjectRequest(r -> r.bucket("fixture-bucket").key("key")))
            .url();
    assertEquals(endpoint.getAuthority(), url.getAuthority());
    assertEquals(endpoint.getPath() + "/fixture-bucket/key", url.getPath());
    assertEquals(url.getAuthority() + url.getPath(), received.poll(5, TimeUnit.SECONDS));
  }
}
