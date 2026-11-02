package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

class EventAdaptersTest {
  @Test
  void subscriptionsRequireAnExplicitMessagingCapability() throws Exception {
    var withMessaging =
        AsyncContractSyncTest.request(AsyncContractSyncTest.contract(), Structure.HEXAGONAL);
    var withoutMessaging =
        new pe.axiz.codegen.model.ProjectRequest(
            withMessaging.name(),
            withMessaging.groupId(),
            withMessaging.basePackage(),
            withMessaging.description(),
            withMessaging.archetype(),
            withMessaging.structure(),
            withMessaging.stack(),
            withMessaging.persistence(),
            List.of(),
            withMessaging.openApi(),
            withMessaging.asyncApi(),
            withMessaging.frameworkVersion(),
            withMessaging.extensions());
    var result = ProjectGenerator.withDefaults().validate(withoutMessaging);
    assertTrue(
        result.problems().stream()
            .anyMatch(problem -> problem.contains("requires a messaging capability")));
    var library =
        new pe.axiz.codegen.model.ProjectRequest(
            "orders-library",
            "pe.axiz",
            "pe.axiz.orders",
            "Library",
            "library",
            Structure.NONE,
            Stack.SERVLET,
            "none",
            List.of(),
            null,
            withMessaging.asyncApi(),
            "2.0.0");
    assertTrue(
        ProjectGenerator.withDefaults().validate(library).problems().stream()
            .anyMatch(problem -> problem.contains("services only")));
  }

  @Test
  void handlersUseTheDeclaredPayloadAndStackAndKeepSpringOutsideTheDomain() throws Exception {
    var contract = AsyncApiDocument.parse(AsyncContractSyncTest.contract());
    for (Structure structure : List.of(Structure.HEXAGONAL, Structure.LAYERED, Structure.EDA)) {
      for (Stack stack : Stack.values()) {
        var files = EventAdapters.generate("pe.axiz.orders", "pe.axiz", structure, stack, contract);
        assertEquals(2, files.size());
        String handler =
            files.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("Handler.java"))
                .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        assertTrue(handler.contains("@HandlesEvent(\"pe.axiz.order.created.v1\")"));
        assertTrue(handler.contains("UnsupportedOperationException"));
        assertFalse(handler.contains("payload.id()"));
        assertFalse(handler.contains(".block("));
        if (stack == Stack.REACTIVE) {
          assertTrue(handler.contains("extends ReactiveTypedEventHandler<OrderCreatedV1>"));
          assertTrue(handler.contains("Publisher<Void> on("));
          assertTrue(handler.contains("Mono.error("));
        } else {
          assertTrue(handler.contains("extends TypedEventHandler<OrderCreatedV1>"));
          assertTrue(handler.contains("void on("));
        }
        String metadata =
            files.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("PayloadConfiguration.java"))
                .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        assertTrue(metadata.contains("@RegisterReflectionForBinding(OrderCreatedV1.class)"));
        assertTrue(metadata.contains("package pe.axiz.orders.config;"));
        String payload =
            EventPayloads.generate("pe.axiz.orders", "pe.axiz", structure, contract)
                .values()
                .stream()
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .findFirst()
                .orElseThrow();
        assertFalse(payload.contains("org.springframework"));
      }
    }
  }

  @Test
  void producersGetBindingMetadataWithoutRegisteringConsumerHandlers() throws Exception {
    var document =
        AsyncApiDocument.parse(
            AsyncContractSyncTest.contract().replace("action: receive", "action: send"));
    var files =
        EventAdapters.generate(
            "pe.axiz.orders", "pe.axiz", Structure.HEXAGONAL, Stack.REACTIVE, document);
    assertEquals(1, files.size());
    assertTrue(files.keySet().iterator().next().endsWith("PayloadConfiguration.java"));
    var empty = AsyncApiDocument.parse("asyncapi: 3.0.0\ninfo: {title: Empty, version: 1.0.0}\n");
    assertTrue(
        EventAdapters.generate("pe.axiz.orders", "pe.axiz", Structure.EDA, Stack.REACTIVE, empty)
            .isEmpty());
  }

  @Test
  void syncCreatesMissingAdaptersAndPreservesTheirBusinessImplementations(@TempDir Path directory)
      throws Exception {
    ProjectGenerator.withDefaults()
        .generate(
            AsyncContractSyncTest.request(AsyncContractSyncTest.contract(), Structure.HEXAGONAL))
        .writeTo(directory, false);
    Path handler =
        directory.resolve(
            "src/main/java/pe/axiz/orders/adapter/in/messaging/OrderCreatedV1Handler.java");
    Path binding =
        directory.resolve(
            "src/main/java/pe/axiz/orders/config/OrderCreatedV1PayloadConfiguration.java");
    assertTrue(Files.exists(handler));
    Files.writeString(
        handler,
        Files.readString(handler) + "\n// Business implementation owned by the developer\n");
    Files.writeString(binding, Files.readString(binding) + "\n// Custom bean configuration\n");
    byte[] handlerBefore = Files.readAllBytes(handler);
    byte[] bindingBefore = Files.readAllBytes(binding);
    var sync = new AsyncContractSync();
    assertTrue(sync.sync(directory).proposed().isEmpty());
    assertTrue(java.util.Arrays.equals(handlerBefore, Files.readAllBytes(handler)));
    assertTrue(java.util.Arrays.equals(bindingBefore, Files.readAllBytes(binding)));
    Files.delete(handler);
    assertEquals(1, sync.sync(directory).created().size());
  }
}
