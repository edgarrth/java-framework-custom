package pe.axiz.rewrite;

import static org.openrewrite.java.Assertions.java;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

class ReviewApiMigrationTest implements RewriteTest {
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
