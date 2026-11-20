package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.audit.AuditTrail;
import pe.axiz.core.json.JsonWriter;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.notification.NotificationChannel;
import pe.axiz.core.notification.NotificationRequest;
import pe.axiz.core.notification.NotificationSender;
import pe.axiz.spring.audit.AxizAuditAutoConfiguration;
import pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration;
import pe.axiz.spring.audit.ReactiveAuditTrail;
import pe.axiz.spring.audit.kafka.AxizAuditKafkaAutoConfiguration;
import pe.axiz.spring.audit.sqs.AxizAuditSqsAutoConfiguration;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.messaging.AxizEvents;
import pe.axiz.spring.messaging.AxizMessagingAutoConfiguration;
import pe.axiz.spring.messaging.AxizNativeMessagingAutoConfiguration;
import pe.axiz.spring.messaging.EventPublisherAdapter;
import pe.axiz.spring.messaging.ReactiveEvents;
import pe.axiz.spring.messaging.kafka.AxizMessagingKafkaAutoConfiguration;
import pe.axiz.spring.messaging.sns.AxizMessagingSnsAutoConfiguration;
import pe.axiz.spring.messaging.sns.AxizNativeMessagingSnsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizMessagingSqsAutoConfiguration;
import pe.axiz.spring.messaging.sqs.AxizNativeMessagingSqsAutoConfiguration;
import pe.axiz.spring.notification.AxizNotificationAutoConfiguration;
import pe.axiz.spring.notification.ReactiveNotifications;
import pe.axiz.spring.test.AxizContainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Broker acknowledgements and received payloads are checked, not only Spring bean presence. */
class MessagingRecipesTest {
  @Test
  void servletKafkaPublisherReceivesBrokerAcknowledgementAndPreservesEnvelope(
      TestReporter evidence) {
    try (var kafka = AxizContainers.kafka()) {
      kafka.start();
      RecipeStacks.runner("servlet")
          .withConfiguration(
              AutoConfigurations.of(
                  KafkaAutoConfiguration.class,
                  AxizMessagingKafkaAutoConfiguration.class,
                  AxizMessagingAutoConfiguration.class))
          .withPropertyValues(
              "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
              "axiz.messaging.kafka.authentication=configured",
              "axiz.config.environment=test",
              "axiz.transport.allow-insecure-data-stores=true",
              "axiz.messaging.destinations.receipts=kafka:recipe-receipts")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var event =
                    EventEnvelope.builder()
                        .id("receipt-1")
                        .source("urn:demo:recipes")
                        .type("demo.receipt.created")
                        .subject("customer-1")
                        .time(Instant.EPOCH)
                        .dataContentType("application/json")
                        .data("{}".getBytes(StandardCharsets.UTF_8))
                        .build();
                var sent = context.getBean(AxizEvents.class).publish("receipts", event);
                assertThat(sent.brokerMessageId()).isNotBlank();
                try (var consumer =
                    new KafkaConsumer<String, String>(
                        Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            kafka.getBootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "recipe-receipts-verifier",
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest",
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class,
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class))) {
                  consumer.subscribe(List.of("recipe-receipts"));
                  boolean received = false;
                  long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                  while (!received && System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofMillis(200))) {
                      assertThat(record.key()).isEqualTo("customer-1");
                      assertThat(
                              new String(
                                  record.headers().lastHeader("ce_id").value(),
                                  StandardCharsets.UTF_8))
                          .isEqualTo("receipt-1");
                      assertThat(record.value()).isEqualTo("{}");
                      received = true;
                    }
                  }
                  assertThat(received).isTrue();
                }
                evidence.publishEntry("axiz.capability.messaging-kafka", "servlet");
              });
    }
  }

  @Test
  void sqsProvidersPublishAndReceiveOnBothStacks(TestReporter evidence) {
    try (var aws = AxizContainers.localstack()) {
      aws.start();
      var identity =
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
      try (var sqs =
          SqsClient.builder()
              .credentialsProvider(identity)
              .region(Region.of(aws.getRegion()))
              .endpointOverride(aws.getEndpoint())
              .httpClientBuilder(UrlConnectionHttpClient.builder())
              .build()) {
        String queue = sqs.createQueue(b -> b.queueName("recipe-direct-events")).queueUrl();
        String[] settings = {
          "axiz.config.environment=test",
          "axiz.transport.allow-plain-http=true",
          "axiz.aws.endpoint=" + aws.getEndpoint(),
          "axiz.aws.region=" + aws.getRegion(),
          "spring.application.name=provider-recipes",
          "axiz.messaging.destinations.receipts=" + queue
        };
        var configurations =
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class,
                AxizMessagingSqsAutoConfiguration.class,
                AxizNativeMessagingSqsAutoConfiguration.class,
                AxizMessagingAutoConfiguration.class,
                AxizNativeMessagingAutoConfiguration.class);
        var event =
            EventEnvelope.builder()
                .id(UUID.randomUUID().toString())
                .source("urn:demo:recipes")
                .type("demo.receipt.created")
                .time(Instant.now())
                .dataContentType("application/json")
                .data("{}".getBytes(StandardCharsets.UTF_8))
                .build();
        new ApplicationContextRunner()
            .withConfiguration(configurations)
            .withBean(AwsCredentialsProvider.class, () -> identity)
            .withPropertyValues(settings)
            .run(
                context -> {
                  context.getBean(AxizEvents.class).publish("receipts", event);
                  assertSqsDelivery(sqs, queue, event.id());
                  evidence.publishEntry("axiz.capability.messaging-sqs", "servlet");
                });
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(configurations)
            .withBean(AwsCredentialsProvider.class, () -> identity)
            .withPropertyValues(settings)
            .run(
                context -> {
                  context
                      .getBean(ReactiveEvents.class)
                      .publish("receipts", event)
                      .block(Duration.ofSeconds(10));
                  assertSqsDelivery(sqs, queue, event.id());
                  evidence.publishEntry("axiz.capability.messaging-sqs", "reactive");
                });
      }
    }
  }

  private static void assertSqsDelivery(SqsClient client, String queue, String id) {
    var messages = client.receiveMessage(b -> b.queueUrl(queue).waitTimeSeconds(5)).messages();
    assertThat(messages).hasSize(1);
    assertThat(CloudEventJson.read(messages.getFirst().body()).id()).isEqualTo(id);
    client.deleteMessage(b -> b.queueUrl(queue).receiptHandle(messages.getFirst().receiptHandle()));
  }

  @Test
  void snsEventsAndSqsAuditReachTheirIndependentDestinations(TestReporter evidence) {
    try (var aws = AxizContainers.localstack()) {
      aws.start();
      var identity =
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(aws.getAccessKey(), aws.getSecretKey()));
      var region = Region.of(aws.getRegion());
      try (var sns =
              SnsClient.builder()
                  .credentialsProvider(identity)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build();
          var sqs =
              SqsClient.builder()
                  .credentialsProvider(identity)
                  .region(region)
                  .endpointOverride(aws.getEndpoint())
                  .httpClientBuilder(UrlConnectionHttpClient.builder())
                  .build()) {
        String events = sqs.createQueue(b -> b.queueName("recipe-events")).queueUrl();
        String audit = sqs.createQueue(b -> b.queueName("recipe-audit")).queueUrl();
        String arn =
            sqs.getQueueAttributes(
                    b -> b.queueUrl(events).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
        String topic = sns.createTopic(b -> b.name("recipe-notifications")).topicArn();
        String policy =
            JsonWriter.writeCanonical(
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
                            arn,
                            "Condition",
                            Map.of("ArnEquals", Map.of("aws:SourceArn", topic))))));
        sqs.setQueueAttributes(
            b -> b.queueUrl(events).attributes(Map.of(QueueAttributeName.POLICY, policy)));
        sns.subscribe(
            b ->
                b.topicArn(topic)
                    .protocol("sqs")
                    .endpoint(arn)
                    .attributes(Map.of("RawMessageDelivery", "true")));
        String[] properties = {
          "axiz.config.environment=test",
          "axiz.transport.allow-plain-http=true",
          "axiz.aws.endpoint=" + aws.getEndpoint(),
          "axiz.aws.region=" + aws.getRegion(),
          "spring.application.name=provider-recipes",
          "axiz.audit.mode=SYNC",
          "axiz.audit.sqs.queue-url=" + audit,
          "axiz.notification.destination=" + topic,
          "axiz.messaging.destinations.receipts=" + topic
        };
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAwsIdentityAutoConfiguration.class,
                    AxizMessagingSnsAutoConfiguration.class,
                    AxizNativeMessagingSnsAutoConfiguration.class,
                    AxizMessagingAutoConfiguration.class,
                    AxizNativeMessagingAutoConfiguration.class,
                    AxizNotificationAutoConfiguration.class,
                    AxizAuditSqsAutoConfiguration.class,
                    AxizAuditAutoConfiguration.class))
            .withBean(AwsCredentialsProvider.class, () -> identity)
            .withPropertyValues(properties)
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  EventEnvelope event =
                      EventEnvelope.builder()
                          .id(UUID.randomUUID().toString())
                          .source("urn:demo:recipes")
                          .type("demo.receipt.created")
                          .time(Instant.now())
                          .dataContentType("application/json")
                          .data("{\"receiptId\":\"demo-1\"}".getBytes(StandardCharsets.UTF_8))
                          .build();
                  context.getBean(EventPublisherAdapter.class).publisher().publish(topic, event);
                  var received =
                      sqs.receiveMessage(b -> b.queueUrl(events).waitTimeSeconds(5)).messages();
                  assertThat(received).hasSize(1);
                  assertThat(CloudEventJson.read(received.getFirst().body()).id())
                      .isEqualTo(event.id());
                  sqs.deleteMessage(
                      b -> b.queueUrl(events).receiptHandle(received.getFirst().receiptHandle()));
                  context.getBean(NotificationSender.class).send(notification("notify-sync"));
                  context
                      .getBean(AuditTrail.class)
                      .record("receipt.created", "receipt")
                      .resourceId("demo-1")
                      .detail("password", "never-serialize-this")
                      .success();
                });
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AxizAwsIdentityAutoConfiguration.class,
                    AxizMessagingSnsAutoConfiguration.class,
                    AxizNativeMessagingSnsAutoConfiguration.class,
                    AxizMessagingAutoConfiguration.class,
                    AxizNativeMessagingAutoConfiguration.class,
                    AxizNotificationAutoConfiguration.class,
                    AxizAuditSqsAutoConfiguration.class,
                    AxizAuditAutoConfiguration.class,
                    AxizNativeAuditAutoConfiguration.class))
            .withBean(AwsCredentialsProvider.class, () -> identity)
            .withPropertyValues(properties)
            .run(
                context -> {
                  assertThat(context).hasNotFailed();
                  context
                      .getBean(ReactiveNotifications.class)
                      .send(notification("notify-reactive"))
                      .block(Duration.ofSeconds(10));
                  context
                      .getBean(ReactiveAuditTrail.class)
                      .record("receipt.read", "receipt")
                      .resourceId("demo-1")
                      .detail("password", "never-serialize-this")
                      .success()
                      .block(Duration.ofSeconds(10));
                });
        var notifications =
            sqs.receiveMessage(b -> b.queueUrl(events).maxNumberOfMessages(10).waitTimeSeconds(5))
                .messages();
        assertThat(notifications).hasSize(2);
        assertThat(notifications.stream().map(message -> CloudEventJson.read(message.body()).id()))
            .containsExactlyInAnyOrder("notify-sync", "notify-reactive");
        for (var message : notifications) {
          var notification = CloudEventJson.read(message.body());
          assertThat(notification.type()).endsWith("notification.requested.v1");
          assertThat(notification.extensions())
              .containsEntry(EventEnvelope.CORRELATION_ID, "recipe-correlation");
          assertThat(new String(notification.data(), StandardCharsets.UTF_8))
              .contains("receipt.ready", "EMAIL", "recipient@example.invalid", "es-PE");
          String variant = notification.id().equals("notify-sync") ? "servlet" : "reactive";
          evidence.publishEntry("axiz.capability.notification", variant);
          evidence.publishEntry("axiz.capability.messaging", variant);
          evidence.publishEntry("axiz.capability.messaging-sns", variant);
        }
        var received =
            sqs.receiveMessage(b -> b.queueUrl(audit).maxNumberOfMessages(10).waitTimeSeconds(5))
                .messages();
        assertThat(received).hasSize(2);
        for (var message : received) {
          assertThat(CloudEventJson.read(message.body()).type())
              .isEqualTo("pe.axiz.audit.event.v1");
          assertThat(message.body()).doesNotContain("never-serialize-this");
          var audited =
              AuditEventJson.read(
                  new String(CloudEventJson.read(message.body()).data(), StandardCharsets.UTF_8));
          String variant =
              switch (audited.action()) {
                case "receipt.created" -> "servlet";
                case "receipt.read" -> "reactive";
                default -> throw new AssertionError("Unexpected audited operation");
              };
          evidence.publishEntry("axiz.capability.audit", variant);
          evidence.publishEntry("axiz.capability.audit-sqs", variant);
        }
      }
    }
  }

  private static NotificationRequest notification(String id) {
    return new NotificationRequest(
        id,
        NotificationChannel.EMAIL,
        "recipient@example.invalid",
        "receipt.ready",
        Locale.forLanguageTag("es-PE"),
        Map.of("receiptId", "demo-1"),
        "recipe-correlation");
  }

  @Test
  void kafkaAuditPublishesSanitizedEventsOnBothStacks(TestReporter evidence) {
    try (var kafka = AxizContainers.kafka()) {
      kafka.start();
      String topic = "recipe-audit";
      String[] properties = {
        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
        "spring.application.name=provider-recipes",
        "axiz.config.environment=test",
        "axiz.transport.allow-insecure-data-stores=true",
        "axiz.audit.kafka.topic=" + topic,
        "axiz.audit.mode=SYNC"
      };
      var configurations =
          AutoConfigurations.of(
              KafkaAutoConfiguration.class,
              AxizAuditKafkaAutoConfiguration.class,
              AxizAuditAutoConfiguration.class,
              AxizNativeAuditAutoConfiguration.class);
      new ApplicationContextRunner()
          .withConfiguration(configurations)
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context
                    .getBean(AuditTrail.class)
                    .record("created", "receipt")
                    .detail("password", "never-serialize-this")
                    .success();
              });
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(configurations)
          .withPropertyValues(properties)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                context
                    .getBean(ReactiveAuditTrail.class)
                    .record("read", "receipt")
                    .detail("password", "never-serialize-this")
                    .success()
                    .block(Duration.ofSeconds(10));
              });
      try (var consumer =
          new KafkaConsumer<String, String>(
              Map.of(
                  ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                  kafka.getBootstrapServers(),
                  ConsumerConfig.GROUP_ID_CONFIG,
                  "recipe-verifier",
                  ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                  "earliest",
                  ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                  StringDeserializer.class,
                  ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                  StringDeserializer.class))) {
        consumer.subscribe(List.of(topic));
        int count = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (count < 2 && System.nanoTime() < deadline) {
          for (var record : consumer.poll(Duration.ofMillis(500))) {
            assertThat(
                    new String(
                        record.headers().lastHeader("ce_type").value(), StandardCharsets.UTF_8))
                .isEqualTo("pe.axiz.audit.event.v1");
            var audited = AuditEventJson.read(record.value());
            assertThat(audited.service()).isEqualTo("provider-recipes");
            assertThat(record.value()).doesNotContain("never-serialize-this");
            String variant =
                switch (audited.action()) {
                  case "created" -> "servlet";
                  case "read" -> "reactive";
                  default -> throw new AssertionError("Unexpected audited operation");
                };
            evidence.publishEntry("axiz.capability.audit-kafka", variant);
            count++;
          }
        }
        assertThat(count).isEqualTo(2);
      }
    }
  }
}
