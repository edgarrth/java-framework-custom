package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
      return new String(
          java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static ProjectRequest request(String contract, Structure structure) {
    ProjectRequest base =
        Fixtures.request("business", structure, Stack.SERVLET, "aurora", List.of("messaging-sqs"));
    return new ProjectRequest(
        base.name(),
        "pe.axiz",
        "pe.axiz.orders",
        base.description(),
        base.archetype(),
        base.structure(),
        base.stack(),
        base.persistence(),
        base.capabilities(),
        base.openApi(),
        contract,
        base.frameworkVersion(),
        base.extensions());
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
    assertTrue(record.contains("java.util.List<java.util.Map<String, Object>> lines"), record);
    assertTrue(record.contains("java.util.List.copyOf(lines)"));
    assertFalse(record.contains("org.jspecify"));
    assertFalse(record.contains("@Nullable"));
    assertTrue(
        AsyncApiSubscriptions.problems(directory, AsyncApiDocument.parse(contract())).isEmpty());
    assertTrue(new AsyncContractSync().sync(directory).applied());
    assertTrue(new AsyncContractSync().sync(directory).proposed().isEmpty());
    var edaBase =
        Fixtures.request("event-processor", Structure.EDA, Stack.REACTIVE, "aurora", List.of());
    var eda =
        new ProjectRequest(
            edaBase.name(),
            edaBase.groupId(),
            edaBase.basePackage(),
            edaBase.description(),
            edaBase.archetype(),
            edaBase.structure(),
            edaBase.stack(),
            edaBase.persistence(),
            edaBase.capabilities(),
            null,
            contract(),
            edaBase.frameworkVersion(),
            edaBase.extensions());
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
    assertThrows(java.io.IOException.class, () -> new AsyncContractSync().sync(directory));
    assertFalse(Files.exists(directory.resolve("outside")));
    ProjectManifest m = ProjectManifest.read(directory);
    new ProjectManifest(
            m.name(),
            m.groupId(),
            m.basePackage(),
            m.archetype(),
            m.structure(),
            m.stack(),
            m.persistence(),
            m.axizVersion(),
            m.openapi(),
            null,
            m.capabilities(),
            m.operations(),
            m.extensions())
        .write(directory);
    assertThrows(IllegalArgumentException.class, () -> new AsyncContractSync().sync(directory));
  }
}
