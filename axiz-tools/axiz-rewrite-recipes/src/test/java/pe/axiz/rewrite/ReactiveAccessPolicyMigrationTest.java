package pe.axiz.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openrewrite.java.Assertions.java;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

class ReactiveAccessPolicyMigrationTest implements RewriteTest {
  @Test
  void monoReturnRemainsCovariantAfterMovingToTheApi(@TempDir Path directory) {
    String before =
        """
        import reactor.core.publisher.Mono;
        import pe.axiz.framework.cqrs.Caller;
        import pe.axiz.framework.cqrs.spring.ReactiveAccessPolicy;
        class OwnerPolicy implements ReactiveAccessPolicy<String> {
          public Class<String> requestType() { return String.class; }
          public Mono<Void> check(String request, Caller caller) { return Mono.empty(); }
        }
        """;
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("reactor-core", "reactive-streams", "axiz-extension-cqrs-api")
                        .dependsOn(
                            "package pe.axiz.framework.cqrs; public record Caller(String subject) {}",
                            "package pe.axiz.framework.cqrs.spring; public interface ReactiveAccessPolicy<R> { "
                                + "Class<R> requestType(); reactor.core.publisher.Mono<Void> check(R request,"
                                + "pe.axiz.framework.cqrs.Caller caller); }")),
        java(
            before,
            source ->
                source
                    .after(
                        after -> {
                          assertTrue(
                              after.contains(
                                  "import pe.axiz.extension.cqrs.ReactiveAccessPolicy;"));
                          assertFalse(
                              after.contains("pe.axiz.extension.cqrs.spring.ReactiveAccessPolicy"));
                          return after;
                        })
                    .afterRecipe(
                        unit -> {
                          Path file = directory.resolve("OwnerPolicy.java");
                          Files.writeString(file, unit.printAll());
                          assertEquals(
                              0,
                              javax.tools.ToolProvider.getSystemJavaCompiler()
                                  .run(
                                      null,
                                      null,
                                      null,
                                      "-classpath",
                                      System.getProperty("java.class.path"),
                                      "-d",
                                      directory.toString(),
                                      file.toString()));
                        })));
  }
}
