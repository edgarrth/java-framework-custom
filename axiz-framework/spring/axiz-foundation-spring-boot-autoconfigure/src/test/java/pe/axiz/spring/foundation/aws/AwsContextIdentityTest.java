package pe.axiz.spring.foundation.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.aws.transport.AwsClients;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.test.support.AwsIdentityFixture;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sts.StsClient;

class AwsContextIdentityTest {
  @TempDir Path temporary;

  @Test
  void applicationProviderIsNotClosedByALeaseAndItsViewsCannotOutliveTheContext() {
    var bean = new AtomicInteger();
    var source =
        (software.amazon.awssdk.auth.credentials.AwsCredentialsProvider)
            () -> AwsBasicCredentials.create("application", "fixture");
    try (var defaults = mockStatic(AwsCredentialSources.class);
        var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(
          "applicationIdentity",
          software.amazon.awssdk.auth.credentials.AwsCredentialsProvider.class,
          () -> {
            bean.incrementAndGet();
            return source;
          });
      context.register(AxizAwsIdentityAutoConfiguration.class);
      context.refresh();
      var factory =
          context
              .getBeanFactory()
              .getBean(AwsIdentity.class)
              .factory(AwsService.S3, AwsCapability.STORAGE);
      try (var resource = factory.get()) {
        assertEquals("application", resource.provider().resolveCredentials().accessKeyId());
        context.close();
        assertThrows(SdkClientException.class, () -> resource.provider().resolveCredentials());
        assertThrows(SdkClientException.class, factory::get);
      }
      assertEquals(1, bean.get());
      defaults.verifyNoInteractions();
    }
  }

  @Test
  void contextsOwnIndependentSourcesAndConsumersAlwaysCloseTheirOwnResource() {
    var created = new AtomicInteger();
    var closed = new AtomicInteger();
    try (var sources = mockStatic(AwsCredentialSources.class);
        var first = new AnnotationConfigApplicationContext();
        var second = new AnnotationConfigApplicationContext()) {
      sources
          .when(() -> AwsCredentialSources.create(TransportSecurityPolicy.system()))
          .thenAnswer(
              call -> {
                String key = "identity-" + created.incrementAndGet();
                return AwsCredentialSource.owned(
                    () -> AwsBasicCredentials.create(key, "fixture"), closed::incrementAndGet);
              });
      first.register(AxizAwsIdentityAutoConfiguration.class);
      first.refresh();
      second.register(AxizAwsIdentityAutoConfiguration.class);
      second.refresh();
      var a =
          first
              .getBeanFactory()
              .getBean(AwsIdentity.class)
              .source(AwsService.S3, AwsCapability.STORAGE);
      var b =
          first
              .getBeanFactory()
              .getBean(AwsIdentity.class)
              .source(AwsService.KMS, AwsCapability.CRYPTO);
      var c =
          second
              .getBeanFactory()
              .getBean(AwsIdentity.class)
              .source(AwsService.SQS, AwsCapability.MESSAGING);
      assertEquals(2, created.get());
      assertEquals("identity-1", b.provider().resolveCredentials().accessKeyId());
      a.close();
      a.close();
      assertEquals(0, closed.get());
      assertThrows(SdkClientException.class, () -> a.provider().resolveCredentials());
      first.close();
      assertEquals(1, closed.get());
      assertThrows(SdkClientException.class, () -> b.provider().resolveCredentials());
      assertEquals("identity-2", c.provider().resolveCredentials().accessKeyId());
      b.close();
      c.close();
      second.close();
      assertEquals(2, closed.get());
    }
  }

  @Test
  void sourceClosesAfterSpringConsumersAndCallbackFactoriesIssueIndependentResources() {
    var events = new ArrayList<String>();
    try (var sources = mockStatic(AwsCredentialSources.class)) {
      sources
          .when(() -> AwsCredentialSources.create(TransportSecurityPolicy.system()))
          .thenAnswer(
              call ->
                  AwsCredentialSource.owned(
                      () -> AwsBasicCredentials.create("identity", "fixture"),
                      () -> events.add("identity")));
      try (var context = new AnnotationConfigApplicationContext()) {
        context.registerBean(
            "consumer",
            Consumer.class,
            () ->
                new Consumer(
                    context
                        .getBeanFactory()
                        .getBean(AwsIdentity.class)
                        .source(AwsService.S3, AwsCapability.STORAGE),
                    events),
            definition -> definition.setDestroyMethodName("close"));
        context.register(AxizAwsIdentityAutoConfiguration.class);
        context.refresh();
        var factory =
            context
                .getBeanFactory()
                .getBean(AwsIdentity.class)
                .factory(AwsService.MSK, AwsCapability.MESSAGING);
        try (var first = factory.get();
            var second = factory.get()) {
          first.close();
          assertEquals("identity", second.provider().resolveCredentials().accessKeyId());
        }
        assertTrue(events.isEmpty());
      }
      assertEquals(java.util.List.of("consumer", "identity"), events);
    }
  }

  @Test
  void synchronousAndAsynchronousClientsShareOneRealStsSession() throws Exception {
    try (var fixture = new AwsIdentityFixture(true)) {
      Path log = temporary.resolve("shared-identity.log");
      var builder =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty(
                      "surefire.test.class.path", System.getProperty("java.class.path")),
                  Probe.class.getName())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile());
      builder.environment().clear();
      builder.environment().putAll(fixture.environment());
      Process process = builder.start();
      try {
        assertTrue(process.waitFor(40, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), Files.readString(log));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
      assertEquals(1, fixture.stsRequests());
      assertEquals(0, fixture.imdsRequests());
      assertEquals(0, fixture.invalidRequests());
    }
  }

  static final class Consumer implements AutoCloseable {
    private final AwsCredentialSource source;
    private final java.util.List<String> events;

    Consumer(AwsCredentialSource source, java.util.List<String> events) {
      this.source = source;
      this.events = events;
    }

    @Override
    public void close() {
      assertEquals("identity", source.provider().resolveCredentials().accessKeyId());
      source.close();
      events.add("consumer");
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) {
      try (var context = new AnnotationConfigApplicationContext()) {
        context.register(AxizAwsIdentityAutoConfiguration.class);
        context.refresh();
        try (var first = client(context);
            var second = client(context);
            var async =
                AwsAsyncResources.create(
                    "shared",
                    1,
                    4,
                    null,
                    context.getBeanFactory().getBean(AwsIdentity.class),
                    AwsService.SQS,
                    AwsCapability.MESSAGING,
                    AsyncAwsTransportSettings.defaults())) {
          assertEquals(
              "ASIAFIXTURE",
              first
                  .serviceClientConfiguration()
                  .credentialsProvider()
                  .resolveIdentity()
                  .join()
                  .accessKeyId());
          first.close();
          assertEquals(
              "ASIAFIXTURE",
              second
                  .serviceClientConfiguration()
                  .credentialsProvider()
                  .resolveIdentity()
                  .join()
                  .accessKeyId());
          assertEquals(
              "ASIAFIXTURE", async.credentialsProvider().resolveCredentials().accessKeyId());
        }
      }
    }

    private static StsClient client(AnnotationConfigApplicationContext context) {
      return AwsClients.build(
          context
              .getBeanFactory()
              .getBean(AwsIdentity.class)
              .configure(StsClient.builder(), AwsService.S3, AwsCapability.STORAGE),
          "us-east-1",
          System.getenv("AWS_ENDPOINT_URL_STS"),
          Duration.ofSeconds(3));
    }
  }
}
