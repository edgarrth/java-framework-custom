package pe.axiz.rewrite;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.maven.Assertions.pomXml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

class ReviewApiMigrationTest implements RewriteTest {
  @ParameterizedTest
  @ValueSource(strings = {"Checks", "Strings", "Dates", "Result"})
  void languageHelpersMoveOutOfCommons(String name) {
    String generic = name.equals("Result") ? "<String, String>" : "";
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-core-lang")
                        .dependsOn(
                            "package pe.axiz.core.commons; public class " + name + "<T,E> {}")),
        java(
            "import pe.axiz.core.commons."
                + name
                + ";\nclass Example { "
                + name
                + generic
                + " value; }",
            "import pe.axiz.core.lang."
                + name
                + ";\n\nclass Example { "
                + name
                + generic
                + " value; }"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Ok", "Err"})
  void nestedResultTypesMoveWithTheirLanguageOwner(String nested) {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-core-lang")
                        .dependsOn(
                            "package pe.axiz.core.commons; public interface Result<T,E> { record"
                                + " Ok<T,E>(T value) implements Result<T,E> {} record Err<T,E>(E"
                                + " error) implements Result<T,E> {} }")),
        java(
            "import pe.axiz.core.commons.Result."
                + nested
                + ";\nclass Example { "
                + nested
                + "<String,String> value; }",
            "import pe.axiz.core.lang.Result."
                + nested
                + ";\nclass Example { "
                + nested
                + "<String,String> value; }"));
  }

  @Test
  void languageMigrationAddsItsOwnDependencyWithoutRemovingOperationalCommons() {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .cycles(3)
                .expectedCyclesThatMakeChanges(2)
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-core-lang")
                        .dependsOn("package pe.axiz.core.commons; public class Checks {}")),
        mavenProject(
            "consumer",
            pomXml(
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>consumer</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>pe.axiz</groupId>
                      <artifactId>axiz-core-commons</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>consumer</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>pe.axiz</groupId>
                      <artifactId>axiz-core-commons</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>pe.axiz</groupId>
                      <artifactId>axiz-core-lang</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """),
            srcMainJava(
                java(
                    "import pe.axiz.core.commons.Checks;\nclass Example { Checks value; }",
                    "import pe.axiz.core.lang.Checks;\n\nclass Example { Checks value; }"))));
  }

  @Test
  void lockDeadlineUsesTheSingleLeaseUtility() {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-core-commons")
                        .dependsOn(
                            "package pe.axiz.core.lock; public class LockTiming { public static"
                                + " long validUntil(long sent, java.time.Duration lease) { return"
                                + " sent; } }")),
        java(
            "import pe.axiz.core.lock.LockTiming;\n"
                + "class Example { long deadline() { return LockTiming.validUntil(1,"
                + " java.time.Duration.ofSeconds(1)); } }",
            "import pe.axiz.core.commons.LeaseTiming;\n\n"
                + "class Example { long deadline() { return LeaseTiming.validUntil(1,"
                + " java.time.Duration.ofSeconds(1)); } }"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pe.axiz.core.persistence.r2dbc", "pe.axiz.kit.r2dbc"})
  void iamAuthenticationMovesToItsAwsAdapterFromEitherPreviousNamespace(String previous) {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-adapter-database-auth-aws")
                        .dependsOn(
                            "package " + previous + "; public class RdsIamPasswordProvider {}")),
        java(
            """
            import %s.RdsIamPasswordProvider;
            class Credentials {
                RdsIamPasswordProvider provider;
            }
            """
                .formatted(previous),
            """
            import pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider;

            class Credentials {
                RdsIamPasswordProvider provider;
            }
            """));
  }

  @Test
  void movesOnlyIdentifierValidationAndKeepsJdbcErrorClassificationInTheKit() {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("axiz-core-commons")
                        .dependsOn(
                            """
                            package pe.axiz.kit.jdbc;
                            public class SqlStates {
                              public static String identifier(String name) { return name; }
                              public static boolean isDuplicateKey(java.sql.SQLException failure) { return false; }
                            }
                            """)),
        java(
            """
            import pe.axiz.kit.jdbc.SqlStates;
            class Example {
                String name(String value) { return SqlStates.identifier(value); }
                boolean duplicate(java.sql.SQLException failure) { return SqlStates.isDuplicateKey(failure); }
            }
            """,
            """
            import pe.axiz.core.commons.SqlIdentifiers;
            import pe.axiz.kit.jdbc.SqlStates;

            class Example {
                String name(String value) { return SqlIdentifiers.require(value); }
                boolean duplicate(java.sql.SQLException failure) { return SqlStates.isDuplicateKey(failure); }
            }
            """));
  }

  @Test
  void nativeSequenceContractsMoveTogetherWithoutChangingTheMonoContract() {
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("reactor-core", "r2dbc-spi", "axiz-adapter-sequence-r2dbc")
                        .dependsOn(
                            """
                            package pe.axiz.kit.r2dbc;
                            public interface ReactiveSequenceStore {
                              reactor.core.publisher.Mono<Long> increment(String name, String period, int increment);
                            }
                            """,
                            """
                            package pe.axiz.kit.r2dbc;
                            public class R2dbcSequenceStore implements ReactiveSequenceStore {
                              public R2dbcSequenceStore(io.r2dbc.spi.ConnectionFactory factory, String table) {}
                              public reactor.core.publisher.Mono<Long> increment(String name, String period, int amount) {
                                return reactor.core.publisher.Mono.empty();
                              }
                            }
                            """)),
        java(
            """
            import pe.axiz.kit.r2dbc.ReactiveSequenceStore;
            import pe.axiz.kit.r2dbc.R2dbcSequenceStore;
            class Sequence {
                ReactiveSequenceStore store(io.r2dbc.spi.ConnectionFactory factory) {
                    return new R2dbcSequenceStore(factory, "axiz_sequence");
                }
            }
            """,
            """
            import pe.axiz.adapter.sequence.r2dbc.R2dbcSequenceStore;
            import pe.axiz.adapter.sequence.r2dbc.ReactiveSequenceStore;

            class Sequence {
                ReactiveSequenceStore store(io.r2dbc.spi.ConnectionFactory factory) {
                    return new R2dbcSequenceStore(factory, "axiz_sequence");
                }
            }
            """));
  }
}
