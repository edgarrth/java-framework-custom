package pe.axiz.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.openrewrite.java.Assertions.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

/** Frozen demo sources exercise persistence APIs, not replacement names alone. */
class PersistenceMigrationTest implements RewriteTest {
  private static String fixture(String name) {
    try (var stream =
        Objects.requireNonNull(
            PersistenceMigrationTest.class.getResourceAsStream(
                "/migration/persistence/" + name + ".java.txt"))) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static void compile(Path dir, String name, String source, String... companions)
      throws IOException {
    List<String> args =
        new ArrayList<>(
            List.of("-classpath", System.getProperty("java.class.path"), "-d", dir.toString()));
    for (String fileName : companions) {
      Path file = dir.resolve(fileName + ".java");
      Files.writeString(file, fixture(fileName));
      args.add(file.toString());
    }
    Path file = dir.resolve(name + ".java");
    Files.writeString(file, source);
    args.add(file.toString());
    assertFalse(source.contains("pe.axiz.core.persistence."));
    assertEquals(
        0,
        javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, args.toArray(String[]::new)),
        "Migrated demo persistence must compile against actual 2.0 classes");
  }

  @Test
  void migratesAuditableJpaEntityAndEncryptedConverters(@TempDir Path dir) throws Exception {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("jakarta.persistence-api", "jspecify", "axiz-kit-jpa")
                        .dependsOn(
                            """
                package pe.axiz.core.persistence.jpa;
                public abstract class AuditableEntity extends pe.axiz.kit.jpa.AuditableEntity {}
                """,
                            """
                package pe.axiz.core.persistence.jpa;
                public class EncryptedConverter extends pe.axiz.kit.jpa.EncryptedConverter {}
                """)),
        java(
            fixture("PaymentOrderEntity"),
            source ->
                source
                    .after(before -> before)
                    .afterRecipe(unit -> compile(dir, "PaymentOrderEntity", unit.printAll()))));
  }

  @Test
  void migratesEncryptedR2dbcRowWithItsRealDomain(@TempDir Path dir) throws Exception {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath(
                            "axiz-core-money",
                            "axiz-r2dbc-spring-boot-autoconfigure",
                            "spring-data-relational",
                            "jspecify")
                        .dependsOn(
                            fixture("PaymentHistory"),
                            fixture("PaymentStatus"),
                            """
                package pe.axiz.core.persistence.r2dbc;
                public record EncryptedText(String value) {
                  public static EncryptedText of(String value) { return new EncryptedText(value); }
                }
                """)),
        java(
            fixture("PaymentHistoryRow"),
            source ->
                source
                    .after(before -> before)
                    .afterRecipe(
                        unit ->
                            compile(
                                dir,
                                "PaymentHistoryRow",
                                unit.printAll(),
                                "PaymentHistory",
                                "PaymentStatus"))));
  }

  @Test
  void migratesDynamoRepositoryWithItsRealItemAndPort(@TempDir Path dir) throws Exception {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("dynamodb-enhanced", "spring-context", "jspecify")
                        .dependsOn(
                            fixture("CustomerContact"),
                            fixture("ContactDirectory"),
                            """
                package pe.axiz.core.persistence.dynamodb;
                public abstract class DynamoRepository<T> {
                  public abstract java.util.Optional<T> findById(String key);
                }
                """,
                            """
                package pe.axiz.spring.dynamodb;
                public abstract class DynamoRepositories {
                  public abstract <T> pe.axiz.core.persistence.dynamodb.DynamoRepository<T> create(String table, Class<T> type);
                }
                """)),
        java(
            fixture("DynamoContactDirectory"),
            source ->
                source
                    .after(before -> before)
                    .afterRecipe(
                        unit ->
                            compile(
                                dir,
                                "DynamoContactDirectory",
                                unit.printAll(),
                                "CustomerContact",
                                "ContactDirectory"))));
  }
}
