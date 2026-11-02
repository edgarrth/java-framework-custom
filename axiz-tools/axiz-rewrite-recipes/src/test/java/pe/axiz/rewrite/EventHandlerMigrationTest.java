package pe.axiz.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.openrewrite.java.Assertions.java;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

/** The payload, protected Publisher on and public react signatures survive the migration. */
class EventHandlerMigrationTest implements RewriteTest {
  @ParameterizedTest
  @ValueSource(strings = {"Mono.empty()", "Flux.empty()"})
  void compilesActualReactiveHandlerContractAfterMigration(String publisher, @TempDir Path dir) {
    String before =
        """
        import org.reactivestreams.Publisher;
        import reactor.core.publisher.Mono;
        import reactor.core.publisher.Flux;
        import pe.axiz.core.messaging.EventEnvelope;
        import pe.axiz.framework.eda.HandlesEvent;
        import pe.axiz.framework.eda.ReactiveTypedEventHandler;
        import pe.axiz.framework.eda.spring.ReactiveTransactionalEventHandler;
        @HandlesEvent("pe.axiz.payments.payment-order-created.v1")
        class PaymentOrderCreatedHandler extends ReactiveTypedEventHandler<String> {
            @Override protected Publisher<Void> on(String event, EventEnvelope envelope) {
                return %s;
            }
            Publisher<Void> route(String payload, EventEnvelope envelope) { return react(payload, envelope); }
            ReactiveTransactionalEventHandler<String> transactional() {
                return new ReactiveTransactionalEventHandler<>(this, null, java.time.Duration.ofSeconds(1));
            }
        }
        """
            .formatted(publisher);
    rewriteRun(
        spec ->
            spec.recipeFromResource("/META-INF/rewrite/axiz.yml", "pe.axiz.rewrite.MigrateToAxiz2")
                .parser(
                    JavaParser.fromJavaVersion()
                        .classpath("reactor-core", "reactive-streams", "axiz-core-messaging")
                        .dependsOn(
                            "package pe.axiz.framework.eda; public @interface HandlesEvent { String value(); }",
                            """
                package pe.axiz.framework.eda;
                public abstract class ReactiveTypedEventHandler<T> {
                    protected abstract org.reactivestreams.Publisher<Void> on(T payload, pe.axiz.core.messaging.EventEnvelope envelope);
                    public final org.reactivestreams.Publisher<Void> react(T payload, pe.axiz.core.messaging.EventEnvelope envelope) { return on(payload, envelope); }
                }
                """,
                            """
                package pe.axiz.framework.eda.spring;
                public class ReactiveTransactionalEventHandler<T> {
                    public ReactiveTransactionalEventHandler(pe.axiz.framework.eda.ReactiveTypedEventHandler<T> handler, Object transaction, java.time.Duration timeout) {}
                }
                """)),
        java(
            before,
            source ->
                source
                    .after(
                        after -> {
                          org.junit.jupiter.api.Assertions.assertFalse(
                              after.contains("pe.axiz.framework.eda"));
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("pe.axiz.spring.messaging.ReactiveTypedEventHandler"));
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains(
                                  "pe.axiz.spring.messaging.ReactiveTransactionalEventHandler"));
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("protected Publisher<Void> on("));
                          org.junit.jupiter.api.Assertions.assertTrue(
                              after.contains("return react(payload, envelope)"));
                          return after;
                        })
                    .afterRecipe(
                        unit -> {
                          Path file = dir.resolve("PaymentOrderCreatedHandler.java");
                          Files.writeString(file, unit.printAll());
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
                          assertEquals(
                              0,
                              result,
                              "Migrated handler must compile against the real axiz 2.0 API");
                        })));
  }
}
