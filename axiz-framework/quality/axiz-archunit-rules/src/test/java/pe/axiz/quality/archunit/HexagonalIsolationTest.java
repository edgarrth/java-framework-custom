package pe.axiz.quality.archunit;

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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.core.lang.ValueSnapshots;

class HexagonalIsolationTest {
  @TempDir Path directory;

  boolean violates(Map<String, String> sources) throws Exception {
    var classes = compile(sources);
    return HexagonalRules.rules("sample").stream()
        .anyMatch(rule -> rule.allowEmptyShould(true).evaluate(classes).hasViolation());
  }

  JavaClasses compile(Map<String, String> sources) throws Exception {
    Path compilation = Files.createTempDirectory(directory, "compilation-");
    var arguments = new ArrayList<String>();
    arguments.addAll(List.of("-d", compilation.toString()));
    for (var entry : sources.entrySet()) {
      Path file = compilation.resolve(entry.getKey().replace('.', '/') + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      arguments.add(file.toString());
    }
    int code =
        ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new));
    Assertions.assertEquals(0, code);
    return new ClassFileImporter().importPath(compilation);
  }

  @Test
  void pureValueSnapshotsAreAllowedWithoutOpeningJsonSerializationToTheDomain() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.domain.Event",
                "package sample.domain; public record Event(java.util.Map<String,Object> values) {"
                    + " public Event { values = "
                    + ValueSnapshots.class.getName()
                    + ".map(values); } }")));
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Event",
                "package sample.domain; public class Event { pe.axiz.core.json.JsonCodec codec; }",
                "pe.axiz.core.json.JsonCodec",
                "package pe.axiz.core.json; public class JsonCodec {}")));
  }

  @Test
  void staticAnalysisAnnotationsOnOwnedContractsDoNotIntroduceInfrastructure() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { pe.axiz.core.money.Value value; }",
                "pe.axiz.core.money.Value",
                "package pe.axiz.core.money;"
                    + " @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(\"EI_EXPOSE_REP\")"
                    + " public class Value {}")));
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { pe.axiz.core.money.Value value; }",
                "pe.axiz.core.money.Value",
                "package pe.axiz.core.money; public class Value { edu.umd.cs.findbugs.BugInstance"
                    + " engine; }",
                "edu.umd.cs.findbugs.BugInstance",
                "package edu.umd.cs.findbugs; public class BugInstance {}")));
  }

  @Test
  void allAdapterAndKitNamespacesAreRejectedEvenBehindOwnedCoreContracts() throws Exception {
    for (String namespace :
        List.of(
            "adapter.audit.kafka",
            "adapter.security.jwt",
            "adapter.sequence.redis",
            "adapter.observability.aws",
            "adapter.future.vendor",
            "kit.future")) {
      String target = "pe.axiz." + namespace + ".Infrastructure";
      assertTrue(
          violates(
              Map.of(
                  "sample.application.Service",
                  "package sample.application; public class Service { pe.axiz.core.money.Bridge"
                      + " bridge; }",
                  "pe.axiz.core.money.Bridge",
                  "package pe.axiz.core.money; public class Bridge { " + target + " hidden; }",
                  target,
                  "package pe.axiz." + namespace + "; public class Infrastructure {}")),
          namespace);
    }
  }

  @Test
  void generalRulesRejectAutoConfigurationAndAdaptersOutsideHexagonalCode() throws Exception {
    var classes =
        compile(
            Map.of(
                "sample.composition.Payments",
                "package sample.composition; public class Payments {"
                    + " vendor.LibraryAutoConfiguration config; pe.axiz.adapter.future.Client"
                    + " client; }",
                "vendor.LibraryAutoConfiguration",
                "package vendor; public class LibraryAutoConfiguration {}",
                "pe.axiz.adapter.future.Client",
                "package pe.axiz.adapter.future; public class Client {}"));
    assertTrue(GeneralRules.NO_AUTOCONFIGURATION_DEPENDENCIES.evaluate(classes).hasViolation());
    assertTrue(GeneralRules.NO_TECHNOLOGY_ADAPTERS.evaluate(classes).hasViolation());
  }

  @Test
  void kitsAreAllowedOnlyInOutboundAdaptersAndRepositories() throws Exception {
    for (String pkg : List.of("sample.adapter.out.persistence", "sample.repository")) {
      var classes =
          compile(
              Map.of(
                  pkg + ".Store",
                  "package " + pkg + "; public class Store { pe.axiz.kit.jpa.Row row; }",
                  "pe.axiz.kit.jpa.Row",
                  "package pe.axiz.kit.jpa; public class Row {}"));
      assertFalse(
          GeneralRules.KITS_ONLY_IN_PERSISTENCE_PACKAGES.evaluate(classes).hasViolation(), pkg);
    }
    var invalid =
        compile(
            Map.of(
                "sample.service.Payments",
                "package sample.service; public class Payments { pe.axiz.kit.jpa.Row row; }",
                "pe.axiz.kit.jpa.Row",
                "package pe.axiz.kit.jpa; public class Row {}"));
    assertTrue(GeneralRules.KITS_ONLY_IN_PERSISTENCE_PACKAGES.evaluate(invalid).hasViolation());
  }

  @Test
  void domainAllowsAxizValuesAndNullability() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public record Order(pe.axiz.core.money.SafeMoney amount)"
                    + " {}",
                "pe.axiz.core.money.SafeMoney",
                "package pe.axiz.core.money; public record SafeMoney(java.math.BigDecimal amount)"
                    + " {}")));
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
  void fieldReviewMetadataIsAllowedWithoutOpeningTheCommonsPackage() throws Exception {
    assertFalse(
        violates(
            Map.of(
                "sample.domain.Limits",
                "package sample.domain; public record Limits("
                    + "@pe.axiz.core.commons.ReviewedState(reason = \"Immutable defensive copy\") "
                    + "java.util.Map<String, Integer> values) {"
                    + " public Limits { values = java.util.Map.copyOf(values); } }")));
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Limits",
                "package sample.domain; public class Limits { pe.axiz.core.commons.Registry state; }",
                "pe.axiz.core.commons.Registry",
                "package pe.axiz.core.commons; public class Registry {}")));
  }

  @Test
  void axizCannotHidePersistenceFromTheDomain() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.domain.Order",
                "package sample.domain; public class Order { pe.axiz.core.money.UnsafeMoney amount;"
                    + " }",
                "pe.axiz.core.money.UnsafeMoney",
                "package pe.axiz.core.money; public class UnsafeMoney { java.sql.Connection"
                    + " connection; }")));
  }

  @Test
  void applicationRejectsFrameworkInfrastructure() throws Exception {
    assertTrue(
        violates(
            Map.of(
                "sample.application.Service",
                "package sample.application; public class Service {"
                    + " org.springframework.context.Context context; }",
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
                "package sample.adapter.in; public class Controller { sample.adapter.out.Client"
                    + " client; }",
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
                    "package sample.domain; public class Order { pe.axiz.core.money.Bridge value;"
                        + " }",
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
                "package sample.application.port.out;"
                    + " @pe.axiz.structure.hexagonal.annotation.OutboundPort public interface Store"
                    + " {}",
                "pe.axiz.structure.hexagonal.annotation.OutboundPort",
                "package pe.axiz.structure.hexagonal.annotation; public @interface OutboundPort"
                    + " {}")));
  }
}
