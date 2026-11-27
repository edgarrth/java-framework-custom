package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.asyncapi.AsyncApiSubscriptions;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.project.ProjectManifest;

class AsyncContractSyncTest {
  static String contract() throws Exception {
    try (var in = AsyncContractSyncTest.class.getResourceAsStream("/events.yaml")) {
      return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static ProjectRequest request(String contract, Structure structure) {
    ProjectRequest base =
        Fixtures.request("business", structure, Stack.SERVLET, "aurora", List.of("messaging-sqs"));
    return ProjectRequest.builder()
        .name(base.name())
        .groupId("pe.axiz")
        .basePackage("pe.axiz.orders")
        .description(base.description())
        .archetype(base.archetype())
        .structure(base.structure())
        .stack(base.stack())
        .persistence(base.persistence())
        .capabilities(base.capabilities())
        .openApi(base.openApi())
        .asyncApi(contract)
        .frameworkVersion(base.frameworkVersion())
        .extensions(base.extensions())
        .build();
  }

  @Test
  void rejectsPayloadSchemasThatCannotBeMappedFaithfullyToJavaRecords() throws Exception {
    String source = contract();
    for (String replacement :
        List.of(
            "id: {type: [string, integer]}",
            "id: {}",
            "id: {type: array}",
            "id: {oneOf: [{type: string}, {type: integer}]}",
            "id: {type: unknown}")) {
      var document = AsyncApiDocument.parse(source.replace("id: {type: string}", replacement));
      assertThrows(
          IllegalArgumentException.class,
          () -> EventPayloads.generate("pe.axiz.orders", "pe.axiz", Structure.HEXAGONAL, document));
    }
    var nullable =
        AsyncApiDocument.parse(
            source.replace("id: {type: string}", "id: {type: [string, 'null']}"));
    assertTrue(
        EventPayloads.generate("pe.axiz.orders", "pe.axiz", Structure.HEXAGONAL, nullable)
            .values()
            .stream()
            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
            .anyMatch(text -> text.contains("String id")));
  }

  @Test
  void generatesPureJavaRecordsAndSubscriptionsWithoutExampleEvents(@TempDir Path directory)
      throws Exception {
    var project =
        ProjectGenerator.withDefaults().generate(request(contract(), Structure.HEXAGONAL));
    project.writeTo(directory, false);
    String record = project.text("src/main/java/pe/axiz/orders/domain/event/OrderCreatedV1.java");
    assertTrue(record.contains("String id"), record);
    assertTrue(record.contains("List<Map<String, Object>> lines"), record);
    assertTrue(record.contains("ValueSnapshots.map(element0)"));
    assertTrue(record.contains("element0 == null ? null"));
    assertFalse(record.contains("org.jspecify"));
    assertFalse(record.contains("@Nullable"));
    assertTrue(
        AsyncApiSubscriptions.problems(directory, AsyncApiDocument.parse(contract())).isEmpty());
    assertTrue(new AsyncContractSync().sync(directory).applied());
    assertTrue(new AsyncContractSync().sync(directory).proposed().isEmpty());
    var edaBase =
        Fixtures.request("event-processor", Structure.EDA, Stack.REACTIVE, "aurora", List.of());
    var eda =
        ProjectRequest.builder()
            .name(edaBase.name())
            .groupId(edaBase.groupId())
            .basePackage(edaBase.basePackage())
            .description(edaBase.description())
            .archetype(edaBase.archetype())
            .structure(edaBase.structure())
            .stack(edaBase.stack())
            .persistence(edaBase.persistence())
            .capabilities(edaBase.capabilities())
            .openApi(null)
            .asyncApi(contract())
            .frameworkVersion(edaBase.frameworkVersion())
            .extensions(edaBase.extensions())
            .build();
    var generated = ProjectGenerator.withDefaults().generate(eda);
    assertFalse(
        generated.files().keySet().stream().anyMatch(path -> path.contains("ExampleCreated")));
  }

  @Test
  void compatiblePayloadEditsProduceProposalsAndNeverOverwriteCode(@TempDir Path directory)
      throws Exception {
    ProjectGenerator.withDefaults()
        .generate(request(contract(), Structure.HEXAGONAL))
        .writeTo(directory, false);
    Path source = directory.resolve("src/main/asyncapi/asyncapi.yaml");
    Path record =
        directory.resolve("src/main/java/pe/axiz/orders/domain/event/OrderCreatedV1.java");
    String original = Files.readString(record);
    Files.writeString(
        source,
        contract()
            .replace("id: {type: string}", "id: {type: string}\n        note: {type: string}"));
    var result = new AsyncContractSync().sync(directory);
    assertTrue(result.applied());
    assertEquals(1, result.proposed().size());
    assertEquals(original, Files.readString(record));
    assertTrue(
        Files.readString(directory.resolve(result.proposed().getFirst())).contains("String note"));
    assertEquals(
        Files.readString(source), Files.readString(directory.resolve(AsyncContractSync.SNAPSHOT)));
    Files.delete(record);
    assertEquals(1, new AsyncContractSync().sync(directory).created().size());
  }

  @Test
  void rejectsBreakingTypesWithoutTouchingTheSnapshotOrCode(@TempDir Path directory)
      throws Exception {
    ProjectGenerator.withDefaults()
        .generate(request(contract(), Structure.LAYERED))
        .writeTo(directory, false);
    Path snapshot = directory.resolve(AsyncContractSync.SNAPSHOT);
    String original = Files.readString(snapshot);
    Files.writeString(
        directory.resolve("src/main/asyncapi/asyncapi.yaml"),
        contract().replace("id: {type: string}", "id: {type: integer}"));
    var result = new AsyncContractSync().sync(directory);
    assertFalse(result.applied());
    assertTrue(result.created().isEmpty());
    assertTrue(result.proposed().isEmpty());
    assertEquals(original, Files.readString(snapshot));
    Files.delete(snapshot);
    assertTrue(new AsyncContractSync().sync(directory).applied());
  }

  @Test
  void validatesManifestAndEveryWriteDestinationBeforeGeneration(@TempDir Path directory)
      throws Exception {
    ProjectGenerator.withDefaults()
        .generate(request(contract(), Structure.LAYERED))
        .writeTo(directory, false);
    Path record = directory.resolve("src/main/java/pe/axiz/orders/event/OrderCreatedV1.java");
    Files.writeString(
        directory.resolve("src/main/asyncapi/asyncapi.yaml"),
        contract()
            .replace("id: {type: string}", "id: {type: string}\n        note: {type: string}"));
    Files.createSymbolicLink(
        record.resolveSibling("OrderCreatedV1.java.axiz-new"), directory.resolve("outside"));
    assertThrows(IOException.class, () -> new AsyncContractSync().sync(directory));
    assertFalse(Files.exists(directory.resolve("outside")));
    ProjectManifest m = ProjectManifest.read(directory);
    ProjectManifest.builder()
        .name(m.name())
        .groupId(m.groupId())
        .basePackage(m.basePackage())
        .archetype(m.archetype())
        .structure(m.structure())
        .stack(m.stack())
        .persistence(m.persistence())
        .axizVersion(m.axizVersion())
        .openapi(m.openapi())
        .asyncapi(null)
        .capabilities(m.capabilities())
        .operations(m.operations())
        .extensions(m.extensions())
        .build()
        .write(directory);
    assertThrows(IllegalArgumentException.class, () -> new AsyncContractSync().sync(directory));
  }
}
