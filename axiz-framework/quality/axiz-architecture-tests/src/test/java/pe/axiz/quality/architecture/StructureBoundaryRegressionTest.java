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
import pe.axiz.quality.archunit.HexagonalRules;

class StructureBoundaryRegressionTest {
  @TempDir Path directory;

  @Test
  void onlyTheNamedTransactionalContractsCanExposeReactorOutsideR2dbc() throws Exception {
    var contract =
        new ClassFileImporter()
            .importClasses(
                pe.axiz.kit.reactor.outbox.ReactiveOutboxWriter.class,
                pe.axiz.kit.reactor.audit.ReactiveAuditChainState.class);
    assertFalse(
        FrameworkArchitectureTest.REACTOR_API_ONLY_IN_R2DBC_TECHNOLOGY
            .evaluate(contract)
            .hasViolation());
    assertFalse(
        FrameworkArchitectureTest.TECHNOLOGY_PUBLIC_API_USES_PORT_TYPES
            .evaluate(contract)
            .hasViolation());
    var other =
        compile(
            Map.of(
                "pe.axiz.kit.reactor.outbox.Unapproved",
                "package pe.axiz.kit.reactor.outbox; public interface Unapproved {"
                    + " reactor.core.publisher.Mono<Void> run(); }",
                "pe.axiz.kit.reactor.audit.Unapproved",
                "package pe.axiz.kit.reactor.audit; public interface Unapproved {"
                    + " reactor.core.publisher.Mono<Void> run(); }"));
    assertTrue(
        FrameworkArchitectureTest.REACTOR_API_ONLY_IN_R2DBC_TECHNOLOGY
            .evaluate(other)
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.TECHNOLOGY_PUBLIC_API_USES_PORT_TYPES
            .evaluate(other)
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.ADAPTERS_ONLY_IMPLEMENT_THEIR_CONTRACT
            .evaluate(
                dependency("pe.axiz.adapter.kafka.msk", "pe.axiz.adapter.aws.identity", false))
            .hasViolation());
  }

  @Test
  void technologyBoundariesRejectForeignKitsAndAllowTheirOwnKit() throws Exception {
    assertFalse(
        FrameworkArchitectureTest.TECHNOLOGY_USES_ONLY_ITS_OWN_KIT
            .evaluate(dependency("pe.axiz.adapter.databaseauth.aws", "pe.axiz.kit.r2dbc", false))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.TECHNOLOGY_USES_ONLY_ITS_OWN_KIT
            .evaluate(dependency("pe.axiz.adapter.observability.aws", "pe.axiz.kit.r2dbc", false))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.TECHNOLOGY_USES_ONLY_ITS_OWN_KIT
            .evaluate(dependency("pe.axiz.adapter.outbox.r2dbc", "pe.axiz.kit.jdbc", false))
            .hasViolation());
    assertTrue(
        FrameworkArchitectureTest.TECHNOLOGY_USES_ONLY_ITS_OWN_KIT
            .evaluate(dependency("pe.axiz.kit.r2dbc", "pe.axiz.kit.jdbc", false))
            .hasViolation());
    assertFalse(
        FrameworkArchitectureTest.TECHNOLOGY_USES_ONLY_ITS_OWN_KIT
            .evaluate(dependency("pe.axiz.adapter.sequence.r2dbc", "pe.axiz.kit.r2dbc", false))
            .hasViolation());
  }

  private boolean hexagonalViolation(String layer, String body) throws Exception {
    var classes =
        compile(
            Map.of(
                "example." + layer + ".Sample",
                "package example." + layer + "; public class Sample { " + body + " }"));
    return HexagonalRules.rules("example").stream()
        .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(classes).hasViolation());
  }

  @Test
  void domainCommonsAreNeutralAndApplicationHasNoResponseOrHeaderContracts() throws Exception {
    for (String type : List.of("api.ApiResponse<?> ", "context.RequestContext "))
      assertTrue(hexagonalViolation("domain", "pe.axiz.core." + type + "value;"));
    assertFalse(
        hexagonalViolation(
            "domain", "void check() { pe.axiz.core.lang.Checks.notNull(this, \"sample\"); }"));
    assertTrue(hexagonalViolation("application", "pe.axiz.core.api.ApiResponse<?> value;"));
    assertFalse(hexagonalViolation("application", "pe.axiz.core.api.PageRequest value;"));
    assertFalse(
        hexagonalViolation(
            "domain",
            """
            pe.axiz.core.lang.Result<String,String> result = new pe.axiz.core.lang.Result.Ok<>("ok");
            String inspect(pe.axiz.core.lang.Result<String,String> value) {
              return switch(value) {
                case pe.axiz.core.lang.Result.Ok<String,String>(var ok) -> ok;
                case pe.axiz.core.lang.Result.Err<String,String>(var error) -> error;
              };
            }
            """));
  }

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

  @Test
  void coreIoRuleRejectsConcreteIoButAllowsNeutralDataAndOnlyTimeoutClassification()
      throws Exception {
    for (String type :
        List.of(
            "java.net.http.HttpClient",
            "java.net.http.HttpRequest",
            "java.nio.file.Path",
            "java.io.FileInputStream",
            "java.net.Socket")) {
      var classes =
          compile(
              Map.of(
                  "pe.axiz.core.sample.Invalid",
                  "package pe.axiz.core.sample; public class Invalid { " + type + " io; }"));
      assertTrue(
          FrameworkArchitectureTest.CORE_DOES_NOT_PERFORM_IO.evaluate(classes).hasViolation(),
          type);
    }
    var neutral =
        compile(
            Map.of(
                "pe.axiz.core.sample.Port",
                "package pe.axiz.core.sample; public interface Port { String send(java.net.URI"
                    + " endpoint); }"));
    assertFalse(
        FrameworkArchitectureTest.CORE_DOES_NOT_PERFORM_IO.evaluate(neutral).hasViolation());
    var classifier =
        compile(
            Map.of(
                "pe.axiz.core.resilience.RetryDecider",
                "package pe.axiz.core.resilience; public class RetryDecider {"
                    + " java.net.http.HttpTimeoutException timeout; }"));
    assertFalse(
        FrameworkArchitectureTest.CORE_DOES_NOT_PERFORM_IO.evaluate(classifier).hasViolation());
    var widened =
        compile(
            Map.of(
                "pe.axiz.core.resilience.RetryDecider",
                "package pe.axiz.core.resilience; public class RetryDecider {"
                    + " java.net.http.HttpClient client; }"));
    assertTrue(FrameworkArchitectureTest.CORE_DOES_NOT_PERFORM_IO.evaluate(widened).hasViolation());
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
            "vendor.client",
            "io.micrometer.core.instrument")) {
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
            List.of("spring.messaging", "spring.messaging.kafka"),
            List.of("spring.messaging.kafka", "spring.messaging.sqs"),
            List.of("structure.hexagonal", "structure.hexagonal.spring"),
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

  @Test
  void integrationsDependOnTheirBaseAndSharedSupportWithoutReversingTheEdge() throws Exception {
    for (var edge :
        List.of(
            List.of("spring.messaging", "spring.messaging.kafka"),
            List.of("spring.messaging.kafka", "spring.messaging.sqs"),
            List.of("spring.audit", "spring.aws"),
            List.of("spring.config", "spring.config.aws"))) {
      assertTrue(
          FrameworkArchitectureTest.STARTERS_ARE_ISOLATED
              .evaluate(dependency("pe.axiz." + edge.get(0), "pe.axiz." + edge.get(1), false))
              .hasViolation(),
          edge.toString());
    }
    for (var edge :
        List.of(
            List.of("spring.messaging.kafka", "spring.messaging"),
            List.of("spring.messaging.msk", "spring.messaging.kafka"),
            List.of("spring.audit.sqs", "spring.aws"))) {
      assertFalse(
          FrameworkArchitectureTest.STARTERS_ARE_ISOLATED
              .evaluate(dependency("pe.axiz." + edge.get(0), "pe.axiz." + edge.get(1), false))
              .hasViolation(),
          edge.toString());
    }
    assertTrue(
        FrameworkArchitectureTest.NEUTRAL_CAPABILITIES_HAVE_NO_PROVIDERS
            .evaluate(
                dependency("pe.axiz.spring.messaging", "pe.axiz.adapter.messaging.kafka", false))
            .hasViolation());
  }
}
