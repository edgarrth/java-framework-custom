package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

class AuditResourcesGenerationTest {
  @Test
  void requiredProductionDestinationsHaveOwnedLocalAndTestResourcesInBothStacks() {
    for (Stack stack : Stack.values()) {
      for (String route : List.of("sqs", "sns", "sink")) {
        var capabilities =
            route.equals("sink") ? List.of("audit-sqs") : List.of("outbox", "messaging-" + route);
        var project =
            ProjectGenerator.withDefaults()
                .generate(
                    Fixtures.request(
                        "business", Structure.HEXAGONAL, stack, "aurora", capabilities));
        String application = project.text("src/main/resources/application.yaml");
        assertTrue(
            application.contains(
                route.equals("sink") ? "${AXIZ_AUDIT_QUEUE_URL}" : "${AXIZ_AUDIT_DESTINATION}"));
        String local = project.text("src/main/resources/application-local.yaml");
        assertTrue(
            local.contains(route.equals("sns") ? "arn:aws:sns:" : "http://localhost:4566/"), local);
        String fixture =
            project.text("src/test/java/pe/axiz/orders/orders/AuditTestConfiguration.java");
        assertTrue(fixture.contains(route.equals("sns") ? "createTopic" : "createQueue"), fixture);
        assertTrue(fixture.contains("ApplicationContextInitializer<GenericApplicationContext>"));
        assertFalse(fixture.contains("AsyncAwsClients"));
        assertFalse(fixture.contains("SqsAsyncClient"));
        assertFalse(fixture.contains("SnsAsyncClient"));
        assertTrue(fixture.contains("registerDisposableBean"));
        assertTrue(fixture.contains("AwsCredentialsProvider.class"));
        assertFalse(fixture.contains("System.setProperty"));
        assertFalse(fixture.contains("System.getProperty"));
        assertTrue(fixture.contains("orders-audit"));
        assertTrue(
            fixture.contains(
                route.equals("sink")
                    ? "axiz.audit.sqs.queue-url"
                    : "axiz.messaging.destinations.audit"));
        assertTrue(
            project
                .text("src/test/java/pe/axiz/orders/orders/OrdersApplicationTestSupport.java")
                .contains("AuditTestConfiguration.class"));
        String consumerTest =
            project.text("src/test/java/pe/axiz/orders/orders/OrdersApplicationTest.java");
        assertTrue(consumerTest.contains("extends OrdersApplicationTestSupport"));
        assertTrue(
            project
                .text("src/test/java/pe/axiz/orders/orders/SensitiveDataCanaryTest.java")
                .contains("extends OrdersApplicationTestSupport"));
        assertFalse(consumerTest.contains("@Isolated"));
        assertFalse(consumerTest.contains("autoConfiguresTheNativeAuditTransport"));
        assertFalse(consumerTest.contains("axiz.transport.pending"));
        assertFalse(consumerTest.contains("serviceClientConfiguration"));
        assertFalse(consumerTest.contains("auditTestAsyncResources"));
        assertTrue(project.text("local/localstack/init.sh").contains("orders-audit"));
      }
    }
  }

  @Test
  void relayInstructionsOnlyAppearForServicesWithAnOutbox() {
    for (boolean outbox : List.of(false, true)) {
      var project =
          ProjectGenerator.withDefaults()
              .generate(
                  Fixtures.request(
                      "business",
                      Structure.HEXAGONAL,
                      Stack.SERVLET,
                      "aurora",
                      outbox ? List.of("outbox", "messaging-sqs") : List.of("audit-sqs")));
      assertEquals(
          outbox,
          project
              .text("src/main/resources/application.yaml")
              .contains("Configure the logical audit destination and the outbox relay transport"));
    }
  }

  @Test
  void independentSqsSinkAndSnsOutboxProvisionBothResourcesWhileKafkaKeepsItsOwnRoute() {
    var generator = ProjectGenerator.withDefaults();
    var both =
        generator.generate(
            Fixtures.request(
                "business",
                Structure.HEXAGONAL,
                Stack.REACTIVE,
                "aurora",
                List.of("outbox", "messaging-sns", "audit-sqs")));
    String fixture = both.text("src/test/java/pe/axiz/orders/orders/AuditTestConfiguration.java");
    assertTrue(fixture.contains("createQueue") && fixture.contains("createTopic"));
    var kafka =
        generator.generate(
            Fixtures.request(
                "business",
                Structure.HEXAGONAL,
                Stack.SERVLET,
                "aurora",
                List.of("outbox", "messaging-kafka")));
    assertFalse(
        kafka.files().keySet().stream()
            .anyMatch(path -> path.endsWith("AuditTestConfiguration.java")));
    assertTrue(
        kafka
            .text("src/main/resources/application.yaml")
            .contains("AXIZ_AUDIT_DESTINATION:kafka:audit"));
  }
}
