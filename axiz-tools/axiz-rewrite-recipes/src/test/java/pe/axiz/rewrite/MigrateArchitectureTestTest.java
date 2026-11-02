package pe.axiz.rewrite;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.yaml.Assertions.yaml;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

class MigrateArchitectureTestTest implements RewriteTest {
  @ParameterizedTest
  @CsvSource({
    "business,servlet,hexagonal,HEXAGONAL",
    "business,reactive,layered,LAYERED",
    "event-processor,servlet,eda,EDA",
    "ux,reactive,bff,BFF",
    "batch,servlet,batch,BATCH",
    "business,reactive,hexagonal,CQRS"
  })
  void takesAllSelectorsFromManifest(
      String role,
      String stack,
      String structure,
      String oldStyle,
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    String before =
        """
        import pe.axiz.quality.archunit.AxizArchitecture;
        import pe.axiz.quality.archunit.Style;

        class ArchitectureTest {
            void architecture() {
                AxizArchitecture.verify("pe.axiz.demo", Style.%s);
            }
        }
        """
            .formatted(oldStyle);
    String extension =
        oldStyle.equals("CQRS") ? ".extensions(pe.axiz.quality.archunit.Extension.CQRS)" : "";
    String after =
        """
        import pe.axiz.quality.archunit.AxizArchitecture;

        class ArchitectureTest {
            void architecture() {
                AxizArchitecture.of("pe.axiz.demo").archetype(pe.axiz.quality.archunit.Archetype.%s).stack(pe.axiz.quality.archunit.Stack.%s).structure(pe.axiz.quality.archunit.Structure.%s)%s.verify();
            }
        }
        """
            .formatted(
                role.toUpperCase(java.util.Locale.ROOT).replace('-', '_'),
                stack.toUpperCase(java.util.Locale.ROOT),
                structure.toUpperCase(java.util.Locale.ROOT),
                extension);
    rewriteRun(
        spec ->
            spec.recipe(new MigrateArchitectureTest())
                .parser(
                    JavaParser.fromJavaVersion()
                        .dependsOn(
                            "package pe.axiz.quality.archunit; public enum Style { HEXAGONAL, LAYERED, EDA, BFF, BATCH, CQRS }",
                            "package pe.axiz.quality.archunit; public class AxizArchitecture { public static void verify(String p, Style s) {} }")),
        yaml(
            "schema: 2\narchetype: "
                + role
                + "\nstack: "
                + stack
                + "\nstructure: "
                + structure
                + "\nextensions: "
                + (oldStyle.equals("CQRS") ? "[cqrs]" : "[]")
                + "\n",
            s -> s.path(".axiz/project.yaml")),
        java(
            before,
            after,
            source ->
                source.afterRecipe(
                    unit -> {
                      java.nio.file.Path file = dir.resolve("ArchitectureTest.java");
                      java.nio.file.Files.writeString(file, unit.printAll());
                      int result =
                          javax.tools.ToolProvider.getSystemJavaCompiler()
                              .run(
                                  null,
                                  null,
                                  null,
                                  "-classpath",
                                  System.getProperty("java.class.path"),
                                  "-d",
                                  dir.toString(),
                                  file.toString());
                      org.junit.jupiter.api.Assertions.assertEquals(
                          0, result, "Migrated architecture test must compile against axiz 2.0");
                    })));
  }

  @org.junit.jupiter.api.Test
  void platformManifestAndAlreadyMigratedSourcesAreLeftIntact() {
    rewriteRun(
        spec -> spec.recipe(new MigrateArchitectureTest()),
        yaml(
            "schema: 2\nkind: platform\narchetype: library\nstructure: none\n",
            path -> path.path(".axiz/project.yaml")),
        java("class OrdinaryLibrary { int size(String text) { return text.length(); } }"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "schema: 1\narchetype: integration\nframework: hexagonal\n",
        "schema: 2\narchetype: integration\nstack: servlet\nstructure: hexagonal\n",
        "schema: 2\narchetype: business\nstack: unknown\nstructure: hexagonal\n",
        "schema: 2\narchetype: business\nstack: servlet\nstructure: cqrs\n"
      })
  void invalidSelectorsAreRejectedBeforeRewritingTests(String manifest) {
    AssertionError failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            AssertionError.class,
            () ->
                rewriteRun(
                    spec -> spec.recipe(new MigrateArchitectureTest()),
                    yaml(manifest, path -> path.path(".axiz/project.yaml")),
                    java("class Application {}")));
    String messages = failure.toString();
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause())
      messages += cause;
    org.junit.jupiter.api.Assertions.assertTrue(
        messages.contains("schema 2") || messages.contains("selector"), messages);
  }

  @org.junit.jupiter.api.Test
  void staticImportUsesTheClosestOwningManifest(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    rewriteRun(
        spec ->
            spec.recipe(new MigrateArchitectureTest())
                .parser(
                    JavaParser.fromJavaVersion()
                        .dependsOn(
                            "package pe.axiz.quality.archunit; public enum Style { HEXAGONAL }",
                            "package pe.axiz.quality.archunit; public class AxizArchitecture { public static void verify(String p, Style s) {} }")),
        yaml(
            "schema: 2\narchetype: business\nstack: servlet\nstructure: hexagonal\n",
            path -> path.path(".axiz/project.yaml")),
        yaml(
            "schema: 2\narchetype: ux\nstack: reactive\nstructure: bff\n",
            path -> path.path("services/channel/.axiz/project.yaml")),
        java(
            """
            import static pe.axiz.quality.archunit.AxizArchitecture.verify;
            import pe.axiz.quality.archunit.Style;
            class ArchitectureTest {
                void test() { verify("pe.axiz.channel", Style.HEXAGONAL); }
            }
            """,
            source ->
                source
                    .path("services/channel/src/test/java/ArchitectureTest.java")
                    .after(
                        after -> {
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("Archetype.UX"), after);
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("Stack.REACTIVE"), after);
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("Structure.BFF"), after);
                          org.junit.jupiter.api.Assertions.assertFalse(
                              after.contains("import static"), after);
                          return after;
                        })
                    .afterRecipe(
                        unit -> {
                          java.nio.file.Path file = dir.resolve("ArchitectureTest.java");
                          java.nio.file.Files.writeString(file, unit.printAll());
                          int result =
                              javax.tools.ToolProvider.getSystemJavaCompiler()
                                  .run(
                                      null,
                                      null,
                                      null,
                                      "-classpath",
                                      System.getProperty("java.class.path"),
                                      "-d",
                                      dir.toString(),
                                      file.toString());
                          org.junit.jupiter.api.Assertions.assertEquals(0, result);
                        })));
  }

  @org.junit.jupiter.api.Test
  void unrelatedVerifyMethodsAreNotRewritten() {
    rewriteRun(
        spec -> spec.recipe(new MigrateArchitectureTest()),
        java(
            "class Health { void verify(String name, int value) {} void test() { verify(\"health\", 1); } }"));
  }
}
