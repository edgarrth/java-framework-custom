package pe.axiz.quality.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.transport.SenderContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.adapter.messaging.sns.SnsEventPublisher;
import pe.axiz.adapter.observability.aws.AsyncAwsClients;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.commons.json.JsonReader;
import pe.axiz.core.commons.json.JsonWriter;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.core.notification.EventNotificationSender;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.observability.AxizObservations;
import pe.axiz.spring.foundation.ReactiveInfrastructureBootstrap;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.messaging.AxizNativeMessagingAutoConfiguration;
import pe.axiz.spring.messaging.AxizNativeMessagingAwsAutoConfiguration;
import pe.axiz.spring.messaging.ReactiveEvents;
import pe.axiz.spring.notification.AxizNotificationAutoConfiguration;
import pe.axiz.spring.notification.ReactiveNotifications;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** Real SQS/SNS routing, notification wire identity and imperative/native FIFO coexistence. */
@ExtendWith(BlockHoundExtension.class)
class LocalStackMessagingIT {
  private static final Duration T = Duration.ofSeconds(20);
  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse(Docker.LOCALSTACK)).withServices("sqs", "sns");
  private static final ObservationRegistry OBSERVATIONS = ObservationRegistry.create();
  private static final List<Observation.Context> CONTEXTS = new CopyOnWriteArrayList<>();
  private static final AtomicInteger REFRESHES = new AtomicInteger();
  private static SqsClient setupSqs;
  private static SnsClient setupSns;
  private static SqsAsyncClient sqs;
  private static SnsAsyncClient sns;
  private static AsyncAwsClients resources;
  private static NettyLifecycleProbe lifecycle;
  private static String queue;
  private static String topic;

  @BeforeAll
  static void start() {
    Docker.require();
    ReactiveInfrastructureBootstrap.initialize();
    LOCALSTACK.start();
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    setupSqs =
        SqsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    setupSns =
        SnsClient.builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials)
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    queue =
        setupSqs
            .createQueue(
                b ->
                    b.queueName("native-messaging.fifo")
                        .attributesWithStrings(Map.of("FifoQueue", "true")))
            .queueUrl();
    topic =
        setupSns
            .createTopic(
                b -> b.name("native-notifications.fifo").attributes(Map.of("FifoTopic", "true")))
            .topicArn();
    String queueArn =
        setupSqs
            .getQueueAttributes(b -> b.queueUrl(queue).attributeNamesWithStrings("QueueArn"))
            .attributesAsStrings()
            .get("QueueArn");
    String policy =
        JsonWriter.write(
            Map.of(
                "Version",
                "2012-10-17",
                "Statement",
                List.of(
                    Map.of(
                        "Effect",
                        "Allow",
                        "Principal",
                        Map.of("Service", "sns.amazonaws.com"),
                        "Action",
                        "sqs:SendMessage",
                        "Resource",
                        queueArn,
                        "Condition",
                        Map.of("ArnEquals", Map.of("aws:SourceArn", topic))))));
    setupSqs.setQueueAttributes(
        b -> b.queueUrl(queue).attributesWithStrings(Map.of("Policy", policy)));
    setupSns.subscribe(
        b ->
            b.topicArn(topic)
                .protocol("sqs")
                .endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
    OBSERVATIONS
        .observationConfig()
        .observationHandler(
            new ObservationHandler<SenderContext<Map<String, String>>>() {
              public boolean supportsContext(Observation.Context context) {
                return context instanceof SenderContext<?>;
              }

              public void onStart(SenderContext<Map<String, String>> context) {
                CONTEXTS.add(context);
                context
                    .getSetter()
                    .set(
                        context.getCarrier(),
                        EventEnvelope.TRACEPARENT,
                        "00-11111111111111111111111111111111-2222222222222222-01");
              }
            });
    lifecycle = new NettyLifecycleProbe();
    resources =
        new AsyncAwsClients(
            () -> {
              REFRESHES.incrementAndGet();
              LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
              return credentials.resolveCredentials();
            },
            Clock.systemUTC(),
            Duration.ofMillis(25));
    sqs =
        resources.configured(
            SqsAsyncClient.builder(), OBSERVATIONS, LocalStackMessagingIT::property, "sqs");
    sns =
        resources.configured(
            SnsAsyncClient.builder(), OBSERVATIONS, LocalStackMessagingIT::property, "sns");
  }

  private static @Nullable String property(String key) {
    return switch (key) {
      case "axiz.aws.region" -> LOCALSTACK.getRegion();
      case "axiz.aws.endpoint" -> LOCALSTACK.getEndpoint().toString();
      case "axiz.aws.async.connection-ttl", "axiz.aws.async.max-idle-time" -> "100ms";
      default -> null;
    };
  }

  @AfterAll
  static void close() {
    if (sqs != null) sqs.close();
    if (sns != null) sns.close();
    if (resources != null) resources.close();
    if (setupSqs != null) setupSqs.close();
    if (setupSns != null) setupSns.close();
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
  void nativeRoutingAndNotificationsShareFifoIdentityWithImperativeProducerAndKeepTraceParent() {
    var parent = Observation.start("caller.messages", OBSERVATIONS);
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMessagingAutoConfiguration.class, AxizNativeMessagingAwsAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class,
                    AxizNotificationAutoConfiguration.class))
        .withBean(SqsAsyncClient.class, () -> sqs)
        .withBean(SnsAsyncClient.class, () -> sns)
        .withBean(ObservationRegistry.class, () -> OBSERVATIONS)
        .withPropertyValues(
            "spring.application.name=accounts",
            "axiz.messaging.type-prefix=pe.axiz",
            "axiz.messaging.destinations.direct=" + queue,
            "axiz.messaging.destinations.notifications=" + topic)
        .run(
            ctx -> {
              Assertions.assertThat(ctx).hasNotFailed();
              var events = ctx.getBean(ReactiveEvents.class);
              var notifications = ctx.getBean(ReactiveNotifications.class);
              var request =
                  new NotificationRequest(
                      "notification-1",
                      NotificationChannel.EMAIL,
                      "user@example.com",
                      "account.created",
                      Locale.ROOT,
                      Map.of("amount", "10.00"),
                      "corr");
              Mono<Void> work =
                  events
                      .publish(
                          "direct", "account.created.v1", "account-1", Map.of("amount", "10.00"))
                      .then()
                      .then(notifications.send(request))
                      .then(Mono.delay(Duration.ofMillis(180)))
                      .then(notifications.send(request));
              StepVerifier.create(
                      work.contextWrite(
                              c ->
                                  c.put(ObservationThreadLocalAccessor.KEY, parent)
                                      .put(
                                          RequestContext.KEY,
                                          RequestMetadata.builder("corr", "req")
                                              .tenantId("tenant")
                                              .build()))
                          .subscribeOn(Schedulers.parallel()))
                  .expectComplete()
                  .verify(T);
              // The same event id from the servlet producer coexists and is deduplicated by
              // SNS/SQS.
              new EventNotificationSender(
                      ctx.getBean(EventFactory.class), new SnsEventPublisher(setupSns), topic)
                  .send(request);
              var missing =
                  new EventEnvelope(
                      "missing",
                      "urn:service",
                      "event.v1",
                      Instant.EPOCH,
                      "account",
                      "application/json",
                      new byte[0],
                      Map.of());
              StepVerifier.create(
                      events
                          .publish(
                              queue.replace("native-messaging.fifo", "does-not-exist.fifo"),
                              missing)
                          .subscribeOn(Schedulers.parallel()))
                  .expectError(PublishException.class)
                  .verify(T);
            });
    parent.stop();
    assertTrue(REFRESHES.get() > 1);
    var producers =
        CONTEXTS.stream().filter(c -> AxizObservations.EVENT_PUBLISH.equals(c.getName())).toList();
    assertEquals(4, producers.size());
    assertTrue(producers.stream().limit(3).allMatch(c -> c.getParentObservation() == parent));
    assertTrue(
        CONTEXTS.stream()
            .filter(c -> AxizObservations.AWS_SDK.equals(c.getName()))
            .allMatch(
                c ->
                    c.getParentObservation() != null
                        && AxizObservations.EVENT_PUBLISH.equals(
                            c.getParentObservation().getContextView().getName())));
    var received = new ArrayList<Message>();
    long deadline = System.nanoTime() + T.toNanos();
    while (received.size() < 2 && System.nanoTime() < deadline) {
      List<Message> batch =
          setupSqs
              .receiveMessage(b -> b.queueUrl(queue).maxNumberOfMessages(10).waitTimeSeconds(1))
              .messages();
      received.addAll(batch);
      batch.forEach(
          message ->
              setupSqs.deleteMessage(
                  b -> b.queueUrl(queue).receiptHandle(message.receiptHandle())));
    }
    assertEquals(2, received.size());
    assertTrue(
        setupSqs.receiveMessage(b -> b.queueUrl(queue).waitTimeSeconds(1)).messages().isEmpty(),
        "Duplicate native/imperative notification event was deduplicated");
    var notification =
        received.stream()
            .map(message -> JsonReader.parseObject(message.body()))
            .filter(body -> "notification-1".equals(body.get("id")))
            .findFirst()
            .orElseThrow();
    assertEquals("pe.axiz.notification.requested.v1", notification.get("type"));
    assertEquals("corr", notification.get("correlationid"));
    assertEquals(
        "00-11111111111111111111111111111111-2222222222222222-01", notification.get("traceparent"));
  }
}
