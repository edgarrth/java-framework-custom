package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureBoundaryRegressionTest {
  @TempDir Path directory;

  private JavaClasses compile(Map<String, String> sources) throws Exception {
    Path compilation = Files.createTempDirectory(directory, "classes-");
    var arguments = new ArrayList<>(List.of("-proc:none", "-d", compilation.toString()));
    for (var entry : sources.entrySet()) {
      Path source = compilation.resolve(entry.getKey().replace('.', '/') + ".java");
      Files.createDirectories(source.getParent());
      Files.writeString(source, entry.getValue());
      arguments.add(source.toString());
    }
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new)));
    return new ClassFileImporter().importPath(compilation);
  }

  private JavaClasses dependency(String from, String to, boolean cycle) throws Exception {
    return compile(
        Map.of(
            from + ".From", "package " + from + "; public class From { " + to + ".To target; }",
            to + ".To",
                "package "
                    + to
                    + "; public class To { "
                    + (cycle ? from + ".From source;" : "")
                    + " }"));
  }

  @Test
  void onlyCqrsMayDependOnTheHexagonalApi() throws Exception {
    for (var edge :
        List.of(
            List.of("structure.hexagonal", "structure.layered"),
            List.of("structure.hexagonal", "extension.cqrs"),
            List.of("extension.cqrs", "structure.layered"))) {
      assertTrue(
          FrameworkArchitectureTest.STRUCTURE_APIS_ARE_PLAIN_JAVA
              .evaluate(dependency("pe.axiz." + edge.get(0), "pe.axiz." + edge.get(1), false))
              .hasViolation(),
          edge.toString());
    }
    assertFalse(
        FrameworkArchitectureTest.STRUCTURE_APIS_ARE_PLAIN_JAVA
            .evaluate(dependency("pe.axiz.extension.cqrs", "pe.axiz.structure.hexagonal", false))
            .hasViolation());
    assertFalse(
        FrameworkArchitectureTest.STRUCTURE_APIS_ARE_PLAIN_JAVA
            .evaluate(dependency("pe.axiz.structure.hexagonal", "pe.axiz.core.boundary", false))
            .hasViolation());
  }

  @Test
  void neutralApisCannotHideRuntimeDependenciesInArrays() throws Exception {
    for (String target :
        List.of(
            "pe.axiz.structure.hexagonal.spring",
            "pe.axiz.adapter.future.vendor",
            "vendor.client")) {
      var imported =
          compile(
              Map.of(
                  "pe.axiz.structure.hexagonal.Port",
                  "package pe.axiz.structure.hexagonal; public interface Port { "
                      + target
                      + ".Row[] rows(); }",
                  target + ".Row",
                  "package " + target + "; public class Row {}"));
      assertTrue(
          FrameworkArchitectureTest.STRUCTURE_APIS_ARE_PLAIN_JAVA.evaluate(imported).hasViolation(),
          target);
    }
  }

  @Test
  void cyclesAreRejectedInEveryLayerAndAcrossStructuresAndExtensions() throws Exception {
    for (var pair :
        List.of(
            List.of("core.one", "core.two"),
            List.of("adapter.lock.redis", "adapter.lock.dynamodb"),
            List.of("kit.jpa", "kit.r2dbc"),
            List.of("spring.cache", "spring.lock"),
            List.of("structure.hexagonal", "extension.cqrs"),
            List.of("kit.jpa", "adapter.audit.r2dbc"))) {
      String first = "pe.axiz." + pair.get(0);
      String second = "pe.axiz." + pair.get(1);
      assertTrue(
          FrameworkArchitectureTest.FRAMEWORK_MODULES_HAVE_NO_CYCLES
              .evaluate(dependency(first, second, true))
              .hasViolation(),
          pair.toString());
      assertFalse(
          FrameworkArchitectureTest.FRAMEWORK_MODULES_HAVE_NO_CYCLES
              .evaluate(dependency(first, second, false))
              .hasViolation(),
          pair.toString());
    }
    assertTrue(
        FrameworkArchitectureTest.STRUCTURES_HAVE_NO_CYCLES
            .evaluate(dependency("pe.axiz.structure.hexagonal", "pe.axiz.extension.cqrs", true))
            .hasViolation());
  }
}
