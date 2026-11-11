package pe.axiz.codegen.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.project.ProjectManifest;

class SampleProjectsTest {
  @Test
  void invalidArgumentsFailWithoutTerminatingTheHostingMavenProcess(@TempDir Path tmp)
      throws Exception {
    assertThrows(IllegalArgumentException.class, () -> SampleProjects.main(new String[0]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SampleProjects.main(new String[] {tmp.toString(), "unused.yaml", "2.0.0", "unknown"}));
  }

  private static String contract() throws Exception {
    try (var in = SampleProjectsTest.class.getResourceAsStream("/contracts/orders.yaml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void onePerArchetypeWithItsDefaults(@TempDir Path tmp) throws Exception {
    var log = new ByteArrayOutputStream();
    List<String> names =
        SampleProjects.generate(
            tmp, contract(), false, "2.0.0", new PrintStream(log, true, StandardCharsets.UTF_8));
    int archetypes = ProjectGenerator.withDefaults().catalog().archetypes().size();
    assertEquals(archetypes, names.size(), log.toString(StandardCharsets.UTF_8));
    assertTrue(names.contains("sample-orders"));
    assertTrue(names.contains("axiz-sample-library"));
    assertTrue(Files.isRegularFile(tmp.resolve("sample-settlement-batch/pom.xml")));
    assertTrue(Files.isRegularFile(tmp.resolve("sample-orders/mvnw")));
    String modules = Files.readString(tmp.resolve("modules.json"));
    assertTrue(modules.startsWith("[\"") && modules.contains("sample-orders"), modules);
  }

  @Test
  void auditModeGeneratesTheSixRealTransportConsumers(@TempDir Path tmp) throws Exception {
    Path contract = tmp.resolve("orders.yaml");
    Files.writeString(contract, contract());
    Path output = tmp.resolve("audit");
    SampleProjects.main(new String[] {output.toString(), contract.toString(), "2.0.0", "audit"});
    for (String stack : List.of("servlet", "reactive")) {
      for (String route : List.of("outbox-sqs", "outbox-sns", "audit-sqs")) {
        Path service = output.resolve("audit-orders-" + stack + "-" + route);
        assertTrue(Files.isRegularFile(service.resolve("pom.xml")));
        try (var sources = Files.walk(service.resolve("src/test/java"))) {
          assertTrue(
              sources.anyMatch(
                  path -> path.getFileName().toString().equals("AuditTestConfiguration.java")));
        }
      }
    }
  }

  @Test
  void everyCombination(@TempDir Path tmp) throws Exception {
    List<String> names =
        SampleProjects.generate(
            tmp,
            contract(),
            true,
            "2.0.0",
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    boolean cqrs = false;
    for (String name : names) {
      var project = ProjectManifest.read(tmp.resolve(name));
      if (project.cqrs()
          && project.stack() == Stack.REACTIVE
          && project.persistence().equals("dynamodb")) cqrs = true;
    }
    assertTrue(cqrs, "must cover CQRS + reactive + DynamoDB");
    assertTrue(names.stream().anyMatch(n -> n.endsWith("-ux")), names.toString());
    assertTrue(names.size() > 20, names.toString());
  }
}
