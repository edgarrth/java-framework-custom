package pe.axiz.codegen.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.JavacTask;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
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
    var javaSources = new ArrayList<Path>();
    for (String name : names) {
      try (var paths = Files.walk(tmp.resolve(name))) {
        javaSources.addAll(paths.filter(path -> path.toString().endsWith(".java")).toList());
      }
    }
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      var task =
          (JavacTask)
              compiler.getTask(
                  null,
                  files,
                  diagnostics,
                  List.of("-proc:none"),
                  null,
                  files.getJavaFileObjectsFromPaths(javaSources));
      task.parse();
      assertTrue(
          diagnostics.getDiagnostics().stream()
              .noneMatch(item -> item.getKind() == Diagnostic.Kind.ERROR),
          () -> "Generated Java syntax errors: " + diagnostics.getDiagnostics());
    }
    boolean cqrs = false;
    for (String name : names) {
      var project = ProjectManifest.read(tmp.resolve(name));
      if (ProjectGenerator.withDefaults()
          .catalog()
          .archetype(project.archetype())
          .orElseThrow()
          .isService()) {
        try (var sources = Files.walk(tmp.resolve(name).resolve("src/test/java"))) {
          var boot =
              sources
                  .filter(path -> path.getFileName().toString().endsWith("ApplicationTest.java"))
                  .findFirst()
                  .orElseThrow();
          String testSource = Files.readString(boot);
          for (String setting :
              List.of(
                  "axiz.security.issuers[0].issuer=https://issuer.test",
                  "axiz.security.issuers[0].jwk-set-uri=https://issuer.test/jwks",
                  "axiz.security.issuers[0].audiences[0]=test",
                  "axiz.config.environment=test",
                  "axiz.transport.allow-plain-http=true",
                  "axiz.transport.allow-insecure-data-stores=true")) {
            assertTrue(
                testSource.contains(setting),
                name + " must declare its test transport policy: " + setting);
          }
        }
      }
      if (project.cqrs()
          && project.stack() == Stack.REACTIVE
          && project.persistence().equals("dynamodb")) cqrs = true;
    }
    assertTrue(cqrs, "must cover CQRS + reactive + DynamoDB");
    assertTrue(names.stream().anyMatch(n -> n.endsWith("-ux")), names.toString());
    assertTrue(names.size() > 20, names.toString());
  }
}
