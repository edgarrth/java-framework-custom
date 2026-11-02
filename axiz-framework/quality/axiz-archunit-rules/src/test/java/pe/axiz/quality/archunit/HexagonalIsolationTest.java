package pe.axiz.quality.archunit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HexagonalIsolationTest {
  @TempDir Path directory;

  boolean violates(Map<String, String> sources) throws Exception {
    var arguments = new ArrayList<String>();
    arguments.addAll(java.util.List.of("-d", directory.toString()));
    for (var entry : sources.entrySet()) {
      Path file = directory.resolve(entry.getKey().replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      arguments.add(file.toString());
    }
    int code =
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new));
    org.junit.jupiter.api.Assertions.assertEquals(0, code);
    var classes = new ClassFileImporter().importPath(directory);
    return HexagonalRules.rules("sample").stream()
        .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(classes).hasViolation());
  }

  @Test
  void domainAllowsAxizValuesAndNullability() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public record Order(pe.axiz.core.money.SafeMoney amount) {}",
                "pe.axiz.core.money.SafeMoney",
                "package pe.axiz.core.money; public record SafeMoney(java.math.BigDecimal amount) {}")));
  }

  @Test
  void domainRejectsAnUnlistedExternalLibrary() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { vendor.orm.Entity entity; }",
                "vendor.orm.Entity",
                "package vendor.orm; public class Entity {}")));
  }

  @Test
  void axizCannotHidePersistenceFromTheDomain() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { pe.axiz.core.money.UnsafeMoney amount; }",
                "pe.axiz.core.money.UnsafeMoney",
                "package pe.axiz.core.money; public class UnsafeMoney { java.sql.Connection connection; }")));
  }

  @Test
  void applicationRejectsFrameworkInfrastructure() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.application.Service",
                "package sample.application; public class Service { org.springframework.context.Context context; }",
                "org.springframework.context.Context",
                "package org.springframework.context; public class Context {}")));
  }

  @Test
  void domainCannotDependOnConfiguration() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { sample.config.Wiring wiring; }",
                "sample.config.Wiring",
                "package sample.config; public class Wiring {}")));
  }

  @Test
  void inboundAdapterCannotCallOutboundAdapter() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.adapter.in.Controller",
                "package sample.adapter.in; public class Controller { sample.adapter.out.Client client; }",
                "sample.adapter.out.Client",
                "package sample.adapter.out; public class Client {}")));
  }

  @Test
  void portCannotExposeTransportModelsInsideGenerics() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.application.port.out.Store",
                "package sample.application.port.out; public interface Store { "
                    + "java.util.List<sample.api.model.Row> rows(); }",
                "sample.api.model.Row",
                "package sample.api.model; public class Row {}")));
  }

  @Test
  void axizCannotHideAnUnlistedThirdParty() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                    "package sample.domain; public class Order { pe.axiz.core.money.Bridge value; }",
                "pe.axiz.core.money.Bridge",
                    "package pe.axiz.core.money; public class Bridge { vendor.orm.Entity entity; }",
                "vendor.orm.Entity", "package vendor.orm; public class Entity {}")));
  }

  @Test
  void portsMayUseOwnedAxizAnnotations() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.application.port.out.Store",
                "package sample.application.port.out; "
                    + "@pe.axiz.structure.hexagonal.annotation.OutboundPort public interface Store {}",
                "pe.axiz.structure.hexagonal.annotation.OutboundPort",
                "package pe.axiz.structure.hexagonal.annotation; public @interface OutboundPort {}")));
  }
}
