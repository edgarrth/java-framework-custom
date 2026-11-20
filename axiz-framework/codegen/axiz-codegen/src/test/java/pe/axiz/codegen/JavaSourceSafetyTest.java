package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.openapi.Json;
import pe.axiz.codegen.render.JavaSourceText;

class JavaSourceSafetyTest {
  static final List<String> COMMENT_INPUTS =
      List.of(
          "*/ public class Injected {} /*",
          "\\u002a/ public class Injected {} /*",
          "\\uuu002a/ public class Injected {} /*",
          "\\u0022",
          "Orders <safe> & readable @team");

  @Test
  void unicodeEscapesStayTextInCommentsAndRoundTripInLiterals(@TempDir Path directory)
      throws Exception {
    for (int index = 0; index < COMMENT_INPUTS.size(); index++) {
      String text = COMMENT_INPUTS.get(index);
      String type = "SafeText" + index;
      Path file = directory.resolve(type + ".java");
      Files.writeString(
          file,
          "/** "
              + JavaSourceText.javadoc(text)
              + " */\npublic class "
              + type
              + " { public static final String VALUE = "
              + JavaSourceText.literal(text)
              + "; }");
      assertEquals(
          0,
          ToolProvider.getSystemJavaCompiler()
              .run(null, null, null, "-proc:none", "-d", directory.toString(), file.toString()));
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()})) {
        Class<?> compiled = loader.loadClass(type);
        assertEquals(text, compiled.getField("VALUE").get(null));
        assertEquals(1, compiled.getDeclaredFields().length);
      }
    }
  }

  @Test
  void serviceCommentsCannotEscapeInAnyStructure() throws Exception {
    for (Structure structure : List.of(Structure.HEXAGONAL, Structure.LAYERED, Structure.BFF)) {
      var base =
          Fixtures.request(
              structure == Structure.BFF ? "ux" : "business",
              structure,
              Stack.SERVLET,
              structure == Structure.BFF ? "none" : "aurora",
              List.of());
      for (String text : COMMENT_INPUTS) {
        String contract =
            Fixtures.orders().replace("summary: Lists orders", "summary: " + Json.write(text));
        var request =
            ProjectRequest.builder()
                .name(base.name())
                .groupId(base.groupId())
                .basePackage(base.basePackage())
                .description(text)
                .archetype(base.archetype())
                .structure(structure)
                .stack(base.stack())
                .persistence(base.persistence())
                .capabilities(base.capabilities())
                .openApi(contract)
                .asyncApi(null)
                .frameworkVersion(base.frameworkVersion())
                .extensions(List.of())
                .build();
        var generated = ProjectGenerator.withDefaults().generate(request);
        assertJavaSyntax(generated.files());
        for (var file : generated.files().entrySet()) {
          if (!file.getKey().endsWith(".java")) continue;
          String source = new String(file.getValue(), StandardCharsets.UTF_8);
          assertFalse(source.contains("\\u002a/") || source.contains("\\uuu002a/"), file.getKey());
        }
      }
    }
  }

  /** Parse actual generated units with javac without requiring the consumer's Spring classpath. */
  static void assertJavaSyntax(Map<String, byte[]> files) throws IOException {
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var compiler = ToolProvider.getSystemJavaCompiler();
    try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
      List<JavaFileObject> units =
          files.entrySet().stream()
              .filter(entry -> entry.getKey().endsWith(".java"))
              .map(
                  entry ->
                      (JavaFileObject)
                          new SimpleJavaFileObject(
                              URI.create("string:///" + entry.getKey()),
                              JavaFileObject.Kind.SOURCE) {
                            @Override
                            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                              return new String(entry.getValue(), StandardCharsets.UTF_8);
                            }
                          })
              .toList();
      var task =
          (JavacTask)
              compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, units);
      for (var unit : task.parse()) {
        assertEquals(
            unit.getSourceFile().getName().endsWith("package-info.java") ? 0 : 1,
            unit.getTypeDecls().size(),
            unit.getSourceFile().getName());
      }
      assertFalse(
          diagnostics.getDiagnostics().stream()
              .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR),
          diagnostics.getDiagnostics().toString());
    }
  }
}
