package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.adapter.aws.transport.AsyncAwsClients;
import pe.axiz.adapter.aws.transport.AsyncAwsTransportSettings;
import pe.axiz.adapter.observability.aws.AwsObservability;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditTrail.Context;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.json.JsonReader;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.quality.containers.TestContainerImages;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import pe.axiz.spring.audit.sqs.AxizAuditSqsAutoConfiguration;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Real native audit delivery, FIFO coexistence and reconnect/credential refresh under BlockHound.
 */
@ExtendWith(BlockHoundExtension.class)
class LocalStackAuditIT {
  private static final Duration T = Duration.ofSeconds(20);
  private static final LocalStackContainer LOCALSTACK =
      TestContainerImages.localstack().withServices("sqs");
  private static final ObservationRegistry OBSERVATIONS = ObservationRegistry.create();
  private static final List<Observation.Context> CONTEXTS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger REFRESHES = new AtomicInteger();
  private static SqsClient setup;
  private static SqsAsyncClient client;
  private static AsyncAwsClients resources;
  private static NettyLifecycleProbe lifecycle;
  private static String queue;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    LOCALSTACK.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    setup =
        SqsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    queue =
        setup
            .createQueue(
                b ->
                    b.queueName("native-audit.fifo")
                        .attributesWithStrings(Map.of("FifoQueue", "true")))
            .queueUrl();
    OBSERVATIONS
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              public void onStart(Observation.Context context) {
                if (AxizObservations.AWS_SDK.equals(context.getName())) CONTEXTS.add(context);
              }
            });
    lifecycle = new NettyLifecycleProbe();
    resources =
        new AsyncAwsClients(
            pe.axiz.adapter.aws.identity.AwsCredentialSource.borrowed(
                () -> {
                  REFRESHES.incrementAndGet();
                  LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                  return credentials.resolveCredentials();
                }),
            Clock.systemUTC(),
            Duration.ofMillis(25),
            AsyncAwsTransportSettings.defaults());
    client =
        AwsObservability.configured(
            resources,
            SqsAsyncClient.builder(),
            OBSERVATIONS,
            key ->
                switch (key) {
                  case "axiz.aws.region" -> LOCALSTACK.getRegion();
                  case "axiz.config.environment" -> "test";
                  case "axiz.transport.allow-plain-http" -> "true";
                  case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
                  case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
                  default -> null;
                },
            "sqs");
  }

  @AfterAll
  static void close() {
    if (client != null) client.close();
    if (resources != null) resources.close();
    if (setup != null) setup.close();
    LOCALSTACK.stop();
    assertTrue(
        BlockHoundExtension.detectedBlockingCalls().isEmpty(),
        BlockHoundExtension.detectedBlockingCalls().toString());
    if (lifecycle != null) {
      lifecycle.close();
      assertTrue(lifecycle.errors.isEmpty(), lifecycle.errors.toString());
    }
  }

  @Test
  void nativeSpringTrailDeliversColdThenReconnectsWithParentAndSanitization() {
    var parent = Observation.start("caller.audit", OBSERVATIONS);
    new ReactiveWebApplicationContextRunner()
        .withPropertyValues("axiz.config.environment=test")
        .withConfiguration(
            AutoConfigurations.of(
                AxizAuditSqsAutoConfiguration.class,
                AxizAuditAutoConfiguration.class,
                AxizNativeAuditAutoConfiguration.class))
        .withBean("axizAuditSqsAsyncClient", SqsAsyncClient.class, () -> client)
        .withBean(ObservationRegistry.class, () -> OBSERVATIONS)
        .withPropertyValues(
            "axiz.audit.mode=ASYNC",
            "spring.application.name=accounts",
            "axiz.audit.sqs.queue-url=" + queue)
        .run(
            ctx -> {
              Assertions.assertThat(ctx).hasNotFailed();
              ReactiveAuditTrail trail = ctx.getBean(ReactiveAuditTrail.class);
              Mono<Void> delivery =
                  Flux.range(0, 12)
                      .flatMap(
                          i ->
                              Mono.deferContextual(
                                  subscriber ->
                                      trail
                                          .record("create", "account")
                                          .resourceId("account-" + i)
                                          .context(
                                              new Context(
                                                  new AuditEvent.Actor("user", "web", null),
                                                  "corr",
                                                  "web",
                                                  "tenant"))
                                          .detail(
                                              "request",
                                              Map.of("password", "do-not-leak", "amount", i))
                                          .success()),
                          4)
                      .then()
                      .then(Mono.delay(Duration.ofMillis(180)))
                      .then(
                          Mono.defer(
                              () ->
                                  trail
                                      .record("read", "account")
                                      .resourceId("after-reconnect")
                                      .success()))
                      .contextWrite(
                          c ->
                              c.put(ObservationThreadLocalAccessor.KEY, parent)
                                  .put(
                                      RequestContext.KEY,
                                      RequestMetadata.builder("corr", "req").build()));
              StepVerifier.create(delivery.subscribeOn(Schedulers.parallel()))
                  .expectComplete()
                  .verify(T);
            });
    parent.stop();
    assertEquals(13, CONTEXTS.size());
    assertTrue(CONTEXTS.stream().allMatch(c -> c.getParentObservation() == parent));
    assertTrue(REFRESHES.get() > 1);
    var received = new ArrayList<Message>();
    long deadline = System.nanoTime() + T.toNanos();
    while (received.size() < 13 && System.nanoTime() < deadline) {
      var batch =
          setup
              .receiveMessage(b -> b.queueUrl(queue).maxNumberOfMessages(10).waitTimeSeconds(1))
              .messages();
      received.addAll(batch);
      batch.forEach(
          m -> setup.deleteMessage(b -> b.queueUrl(queue).receiptHandle(m.receiptHandle())));
    }
    assertEquals(13, received.size());
    assertTrue(
        received.stream()
            .allMatch(
                m ->
                    "pe.axiz.audit.event.v1".equals(JsonReader.parseObject(m.body()).get("type"))));
    assertTrue(
        received.stream()
            .allMatch(m -> "1.0".equals(JsonReader.parseObject(m.body()).get("specversion"))));
    assertFalse(received.toString().contains("do-not-leak"));
  }
}
