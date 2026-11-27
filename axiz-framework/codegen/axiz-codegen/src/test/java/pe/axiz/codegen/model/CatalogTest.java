package pe.axiz.codegen.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CatalogTest {

  private final Catalog catalog = Catalog.load();

  @Test
  void localOverridesUseTheCatalogSchema() {
    assertEquals(
        "local",
        catalog
            .capability("config")
            .orElseThrow()
            .localProperties()
            .get("axiz.config.environment"));
    assertEquals(
        false,
        catalog
            .capability("r2dbc-aws")
            .orElseThrow()
            .localProperties()
            .get("axiz.r2dbc.iam.enabled"));
    assertEquals(
        "local",
        catalog
            .capability("tokenization")
            .orElseThrow()
            .localProperties()
            .get("axiz.tokenization.mode"));
  }

  @Test
  void tokenConsumerDoesNotSelectTheVaultOrItsInfrastructure() {
    var consumer = catalog.resolve(List.of("tokenization"));
    assertTrue(catalog.missingAlternatives(consumer).isEmpty());
    assertFalse(
        consumer.stream()
            .anyMatch(
                c ->
                    c.name().startsWith("tokenization-vault")
                        || c.name().startsWith("crypto")
                        || c.name().equals("dynamodb")));
    assertFalse(
        catalog.missingAlternatives(catalog.resolve(List.of("tokenization-vault"))).isEmpty());
    var selected =
        List.of("tokenization-vault-dynamodb", "tokenization-vault-redis", "audit-kafka");
    assertTrue(catalog.missingAlternatives(catalog.resolve(selected)).isEmpty());
    var mixed = new ArrayList<>(selected);
    mixed.add("tokenization");
    assertFalse(catalog.missingAlternatives(catalog.resolve(mixed)).isEmpty());
  }

  @Test
  void hasTheSevenServiceArchetypesAndTwoPlatformOnes() {
    assertEquals(
        List.of(
            "business",
            "core",
            "gateway",
            "ux",
            "support",
            "event-processor",
            "batch",
            "library",
            "starter"),
        catalog.archetypes().stream().map(Archetype::name).toList());
    assertEquals(7, catalog.archetypes().stream().filter(Archetype::isService).count());
  }

  @Test
  void defaultsFollowTheDefinition() {
    Archetype business = catalog.archetype("business").orElseThrow();
    assertEquals(Structure.HEXAGONAL, business.defaultStructure());
    assertEquals(Stack.SERVLET, business.defaultStack());
    assertEquals("aurora", business.defaultPersistence());
    assertEquals("cronjob", catalog.archetype("batch").orElseThrow().deployment());
    assertFalse(catalog.archetype("event-processor").orElseThrow().rest());
    assertEquals(Structure.BFF, catalog.archetype("ux").orElseThrow().defaultStructure());
  }

  @Test
  void everyDefaultCombinationIsSupportedByItsFramework() {
    for (Archetype a : catalog.archetypes()) {
      assertTrue(a.defaultStructure().stacks().contains(a.defaultStack()), a.name());
    }
  }

  @Test
  void resolvesRequirementsBeforeTheCapability() {
    List<String> names =
        catalog.resolve(List.of("idempotency-dynamodb")).stream().map(Capability::name).toList();
    assertEquals(List.of("idempotency", "dynamodb", "idempotency-dynamodb"), names);
    assertThrows(IllegalArgumentException.class, () -> catalog.resolve(List.of("nope")));
  }

  @Test
  void storeVariantsDependOnThePersistence() {
    Capability inbox = catalog.capability("inbox").orElseThrow();
    assertTrue(inbox.supports("aurora"));
    assertTrue(inbox.supports("dynamodb"));
    assertFalse(inbox.supports("none"));
    assertEquals("dynamodb", inbox.properties("dynamodb").get("axiz.inbox.store"));
    assertTrue(
        inbox.dependencies("dynamodb").contains("pe.axiz:axiz-inbox-dynamodb-spring-boot-starter"));
    assertTrue(catalog.capability("cache").orElseThrow().supports("none"));
  }

  @Test
  void noCapabilityStoresSecretsInProperties() {
    for (Capability c : catalog.capabilities()) {
      c.properties()
          .forEach(
              (k, v) -> {
                String key = k.toLowerCase(Locale.ROOT);
                if (key.contains("password")
                    || key.contains("secret-key")
                    || key.endsWith("token")) {
                  throw new AssertionError(c.name() + " sets a secret-like property " + k);
                }
              });
    }
  }

  /**
   * Dentro del repositorio de la estructura, cada artefacto de axiz referenciado debe existir como
   * módulo.
   */
  @Test
  void referencedAxizArtifactsExist() throws IOException {
    Path root = Path.of("").toAbsolutePath().getParent().getParent();
    if (!Files.isDirectory(root.resolve("spring")) || !Files.isDirectory(root.resolve("core"))) {
      return; // empaquetado fuera del repositorio
    }
    Set<String> modules;
    try (Stream<Path> s =
        Stream.of("core", "spring", "structures", "extensions", "adapters", "kits")
            .flatMap(d -> walk(root.resolve(d)))) {
      modules =
          s.filter(p -> p.getFileName().toString().equals("pom.xml"))
              .map(p -> p.getParent().getFileName().toString())
              .collect(Collectors.toSet());
    }
    for (Capability c : catalog.capabilities()) {
      assertTrue(modules.contains(c.starter()), c.name() + " -> " + c.starter());
      for (String d : c.dependencies()) {
        if (d.startsWith("pe.axiz:")) {
          assertTrue(modules.contains(d.substring(8)), c.name() + " -> " + d);
        }
      }
      for (Capability.Store store : c.persistence().values()) {
        store.dependencies().stream()
            .filter(d -> d.startsWith("pe.axiz:"))
            .forEach(d -> assertTrue(modules.contains(d.substring(8)), c.name() + " -> " + d));
      }
    }
    for (Structure f : Structure.values()) {
      if (f != Structure.NONE) {
        assertTrue(modules.contains(f.starter()), f.starter());
      }
    }
  }

  private static Stream<Path> walk(Path dir) {
    try {
      return Files.walk(dir, 3);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
