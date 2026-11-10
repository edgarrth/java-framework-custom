package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.ArtifactGenerator;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.project.ProjectManifest;

/**
 * Generated persistence code compiles against actual framework APIs for both stacks and layouts.
 */
class GeneratedDynamoRepositoryTest {
  @Test
  void generatedEntitiesAndRepositoriesCompile(@TempDir Path root) throws Exception {
    var generator = new ArtifactGenerator(Clock.systemUTC());
    for (var structure : List.of(Structure.LAYERED, Structure.HEXAGONAL)) {
      for (var stack : Stack.values()) {
        Path output = Files.createDirectory(root.resolve(structure.name() + "-" + stack.name()));
        var project =
            new ProjectManifest(
                "orders",
                "pe.axiz.orders",
                "pe.axiz.orders",
                "business",
                structure,
                stack,
                "dynamodb",
                "2.0.0",
                null,
                null,
                List.of(),
                List.of());
        var args =
            new ArrayList<>(
                List.of(
                    "-proc:none",
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    output.toString()));
        for (String type : List.of("entity", "repository")) {
          var generated =
              generator.generate(project, type, "payment", ArtifactGenerator.Options.none(), null);
          for (var entry : generated.created().entrySet()) {
            if (!entry.getKey().endsWith(".java")) continue;
            Path file = output.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            args.add(file.toString());
            if (type.equals("repository") && stack == Stack.REACTIVE) {
              String source = new String(entry.getValue(), StandardCharsets.UTF_8);
              assertTrue(source.contains("Mono<PaymentItem> findById"));
              assertFalse(source.contains("boundedElastic"));
            }
          }
        }
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)),
            structure + " / " + stack);
      }
    }
  }
}
