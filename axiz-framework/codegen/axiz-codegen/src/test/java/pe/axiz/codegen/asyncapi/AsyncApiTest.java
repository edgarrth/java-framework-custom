package pe.axiz.codegen.asyncapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.openapi.SafeYaml;

class AsyncApiTest {
  static final String CONTRACT =
      """
      asyncapi: 3.0.0
      info: {title: Orders, version: 1.0.0}
      channels:
        orders:
          address: orders
          messages:
            created: {$ref: '#/components/messages/created'}
      operations:
        publish:
          action: send
          channel: {$ref: '#/channels/orders'}
        consume:
          action: receive
          channel: {$ref: '#/channels/orders'}
          messages: [{$ref: '#/channels/orders/messages/created'}]
      components:
        messages:
          created:
            name: pe.axiz.order.created.v1
            payload: {$ref: '#/components/schemas/Created'}
        schemas:
          Created:
            type: object
            required: [id]
            properties:
              id: {type: string}
              lines:
                type: array
                items:
                  type: object
                  properties:
                    amount: {type: number}
      """;

  @Test
  void expansionBudgetCoversTheWholeDocumentRatherThanEachOperation() {
    var yaml = SafeYaml.create(AsyncApiDocument.MAX_SIZE);
    @SuppressWarnings("unchecked")
    Map<String, Object> root = yaml.load(CONTRACT);
    Map<String, Object> operations = new LinkedHashMap<>();
    for (int i = 0; i < 10_000; i++)
      operations.put(
          "publish" + i,
          Map.of(
              "action",
              "send",
              "channel",
              new LinkedHashMap<>(Map.of("$ref", "#/channels/orders"))));
    root.put("operations", operations);
    String repeated = yaml.dump(root);
    assertTrue(repeated.length() < AsyncApiDocument.MAX_SIZE);
    var failure =
        assertThrows(IllegalArgumentException.class, () -> AsyncApiDocument.parse(repeated));
    assertTrue(failure.getMessage().contains("expansion limit"), failure.getMessage());
  }

  @Test
  void readsActualSendReceiveReferencesAndDefensiveSnapshots() {
    var document = AsyncApiDocument.parse(CONTRACT);
    assertEquals("pe.axiz.order.created.v1", document.published().getFirst().type());
    assertEquals(document.published(), document.consumed());
    assertEquals("object", document.published().getFirst().payload().get("type"));
    assertThrows(UnsupportedOperationException.class, () -> document.published().clear());
    var message = document.published().getFirst().message();
    assertThrows(UnsupportedOperationException.class, () -> message.put("name", "changed"));
  }

  @Test
  void optionalFieldsAndDocumentationAreCompatibleIncludingArrayObjects() {
    for (String updated :
        List.of(
            CONTRACT,
            CONTRACT.replace(
                "id: {type: string}", "id: {type: string}\n        note: {type: string}"),
            CONTRACT.replace(
                "amount: {type: number}",
                "amount: {type: number}\n              tax: {type: number}"),
            CONTRACT.replace(
                "name: pe.axiz", "description: Editorial note\n      name: pe.axiz"))) {
      assertFalse(
          ContractDiff.hasBreaking(
              AsyncApiDiff.compare(
                  AsyncApiDocument.parse(CONTRACT), AsyncApiDocument.parse(updated))));
    }
  }

  @Test
  void wireChangesRequireNewTypesEvenWhenTheSnapshotIsEdited() {
    for (String updated :
        List.of(
            CONTRACT.replace("id: {type: string}", "id: {type: integer}"),
            CONTRACT.replace("required: [id]", "required: [id, lines]"),
            CONTRACT.replace("id: {type: string}", "renamed: {type: string}"),
            CONTRACT.replace("amount: {type: number}", "amount: {type: string}"),
            CONTRACT.replace("action: send", "action: receive"),
            CONTRACT.replace("name: pe.axiz.order.created.v1", "name: pe.axiz.order.created.v2"))) {
      assertTrue(
          ContractDiff.hasBreaking(
              AsyncApiDiff.compare(
                  AsyncApiDocument.parse(CONTRACT), AsyncApiDocument.parse(updated))),
          updated);
    }
  }

  @Test
  void retainsV1WhilePublishingV2InParallel() {
    String dual =
        CONTRACT.replace(
            "created: {$ref: '#/components/messages/created'}",
            "created: {$ref: '#/components/messages/created'}\n      v2: {$ref: '#/components/messages/createdV2'}");
    // A second message with the same payload and a new wire identity.
    String extra =
        "    createdV2:\n      name: pe.axiz.order.created.v2\n      payload: {$ref: '#/components/schemas/Created'}\n";
    dual = dual.replace("  schemas:\n", extra + "  schemas:\n");
    var changes =
        AsyncApiDiff.compare(AsyncApiDocument.parse(CONTRACT), AsyncApiDocument.parse(dual));
    assertFalse(ContractDiff.hasBreaking(changes), changes.toString());
    assertTrue(changes.stream().anyMatch(change -> change.location().endsWith(".v2")));
  }

  @Test
  void rejectsInvalidAmbiguousOrUnboundedReferences() {
    for (String invalid :
        List.of(
            CONTRACT.replace("3.0.0", "2.6.0"),
            CONTRACT.replace("action: send", "action: discard"),
            CONTRACT.replace("pe.axiz.order.created.v1", "unversioned"),
            CONTRACT.replace("#/components/schemas/Created", "https://example.test/schema"),
            CONTRACT.replace("#/components/schemas/Created", "#/components/schemas/Missing"),
            CONTRACT.replace(
                "#/components/schemas/Created", "#/components/messages/created/payload"),
            CONTRACT.replace(
                "messages: [{$ref: '#/channels/orders/messages/created'}]", "messages: []"),
            CONTRACT.replace(
                "messages: [{$ref: '#/channels/orders/messages/created'}]",
                "messages: [{$ref: '#/components/messages/created'}]"),
            CONTRACT.replace("asyncapi: 3.0.0", "asyncapi: 3.0.0\nasyncapi: 3.1.0"),
            CONTRACT.replace(
                "payload: {$ref: '#/components/schemas/Created'}", "payload: {type: string}"),
            "[not, a, mapping]",
            "info: [wrong]")) {
      assertThrows(IllegalArgumentException.class, () -> AsyncApiDocument.parse(invalid), invalid);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> AsyncApiDocument.parse("a".repeat(AsyncApiDocument.MAX_SIZE + 1)));
  }

  @Test
  void checksYamlProfilesAndPropertiesAgainstConsumedTypes(@TempDir Path directory)
      throws Exception {
    var document = AsyncApiDocument.parse(CONTRACT);
    assertFalse(AsyncApiSubscriptions.problems(directory, document).isEmpty());
    Path resources = Files.createDirectories(directory.resolve("src/main/resources"));
    Path config = resources.resolve("application.yaml");
    Files.writeString(
        config,
        "axiz:\n  messaging:\n    consumer:\n      subscriptions: [pe.axiz.order.created.v1]\n");
    assertTrue(AsyncApiSubscriptions.problems(directory, document).isEmpty());
    Files.writeString(
        resources.resolve("application-prod.yml"),
        "axiz.messaging.consumer.subscriptions: [wrong.v1]\n");
    assertTrue(
        AsyncApiSubscriptions.problems(directory, document)
            .getFirst()
            .contains("application-prod.yml"));
    Files.delete(resources.resolve("application-prod.yml"));
    Files.writeString(
        config,
        "axiz.messaging:\n  consumer.subscriptions: pe.axiz.order.created.v1,pe.axiz.order.created.v1\n");
    assertFalse(AsyncApiSubscriptions.problems(directory, document).isEmpty());
    Files.delete(config);
    Files.writeString(
        resources.resolve("application.properties"),
        "axiz.messaging.consumer.subscriptions[0]=pe.axiz.order.created.v1\n");
    assertTrue(AsyncApiSubscriptions.problems(directory, document).isEmpty());
    assertTrue(
        AsyncApiSubscriptions.problems(
                directory,
                AsyncApiDocument.parse(CONTRACT.replace("action: receive", "action: send")))
            .getFirst()
            .contains("must equal"));
  }

  @Test
  void activatedProfileDoesNotReplaceTheRequiredBaseSubscriptions(@TempDir Path directory)
      throws Exception {
    Path resources = Files.createDirectories(directory.resolve("src/main/resources"));
    Files.writeString(
        resources.resolve("application.yaml"),
        "spring.config.activate.on-profile: prod\naxiz.messaging.consumer.subscriptions: [pe.axiz.order.created.v1]\n");
    assertTrue(
        AsyncApiSubscriptions.problems(directory, AsyncApiDocument.parse(CONTRACT)).stream()
            .anyMatch(problem -> problem.contains("base application")));
  }

  @Test
  void refusesSymlinkedConfiguration(@TempDir Path directory) throws Exception {
    Path resources = Files.createDirectories(directory.resolve("src/main/resources"));
    Path outside =
        Files.writeString(
            directory.resolve("outside.yaml"), "axiz.messaging.consumer.subscriptions: []\n");
    Files.createSymbolicLink(resources.resolve("application.yaml"), outside);
    assertThrows(
        IOException.class,
        () -> AsyncApiSubscriptions.problems(directory, AsyncApiDocument.parse(CONTRACT)));
  }
}
