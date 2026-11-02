package pe.axiz.codegen.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class SampleProjectsTest {

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
            tmp, contract(), false, "1.0.0", new PrintStream(log, true, StandardCharsets.UTF_8));
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
  void everyCombination(@TempDir Path tmp) throws Exception {
    List<String> names =
        SampleProjects.generate(
            tmp,
            contract(),
            true,
            "1.0.0",
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    boolean cqrs = false;
    for (String name : names) {
      var project = pe.axiz.codegen.project.ProjectManifest.read(tmp.resolve(name));
      if (project.cqrs()
          && project.stack() == pe.axiz.codegen.model.Stack.REACTIVE
          && project.persistence().equals("dynamodb")) cqrs = true;
    }
    assertTrue(cqrs, "must cover CQRS + reactive + DynamoDB");
    assertTrue(names.stream().anyMatch(n -> n.endsWith("-ux")), names.toString());
    assertTrue(names.size() > 20, names.toString());
  }
}
