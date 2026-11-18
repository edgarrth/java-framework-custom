package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.util.unit.DataSize;
import pe.axiz.spring.crypto.kms.AxizCryptoKmsProperties;
import pe.axiz.spring.featureflags.AxizFeatureFlagsProperties;
import pe.axiz.spring.security.AxizSecurityProperties;
import pe.axiz.spring.security.AxizSecurityProperties.Authorization;
import pe.axiz.spring.security.AxizSecurityProperties.RouteRule;
import pe.axiz.spring.security.JwtAccessProfile;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties.TypePolicy;

class ConfigurationSnapshotsTest {
  @TestFactory
  Stream<DynamicTest>
      configurationCollectionsCannotBeChangedThroughConstructorArgumentsOrAccessors() {
    return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pe.axiz.spring", "pe.axiz.structure", "pe.axiz.extension")
            .stream()
            .filter(type -> type.getName().matches(".*Properties(?:\\$.*)?"))
            .map(type -> type.reflect())
            .filter(Class::isRecord)
            .filter(
                type ->
                    Stream.of(type.getRecordComponents()).anyMatch(c -> collection(c.getType())))
            .map(type -> DynamicTest.dynamicTest(type.getName(), () -> check(type)));
  }

  private static boolean collection(Class<?> type) {
    return type == List.class || type == Map.class || type == Set.class;
  }

  @SuppressWarnings("unchecked")
  private static void check(Class<?> type) throws Exception {
    RecordComponent[] components = type.getRecordComponents();
    Object[] arguments = new Object[components.length];
    for (int i = 0; i < components.length; i++) {
      Class<?> parameter = components[i].getType();
      arguments[i] = validArgument(type, components[i]);
      if (type == Authorization.class && components[i].getName().equals("unprotected"))
        arguments[i] = "fail";
      if (type == RouteRule.class) {
        if (components[i].getName().equals("paths"))
          arguments[i] = new ArrayList<>(List.of("/snapshot"));
        if (components[i].getName().equals("authorities"))
          arguments[i] = new ArrayList<>(List.of("SCOPE_snapshot"));
      }
      if (parameter == DataSize.class) arguments[i] = DataSize.ofMegabytes(1);
    }
    Object settings =
        type.getDeclaredConstructor(
                Stream.of(components).map(RecordComponent::getType).toArray(Class<?>[]::new))
            .newInstance(arguments);
    for (int i = 0; i < components.length; i++) {
      if (!collection(components[i].getType())) {
        continue;
      }
      Object exposed = components[i].getAccessor().invoke(settings);
      if (arguments[i] instanceof Map<?, ?> map) {
        Map<Object, Object> snapshot = (Map<Object, Object>) exposed;
        Map<Object, Object> before = new LinkedHashMap<>(snapshot);
        ((Map<Object, Object>) map).put("changed", "value");
        assertEquals(before, snapshot, components[i].getName());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("changed", "value"));
      } else {
        Collection<Object> snapshot = (Collection<Object>) exposed;
        List<Object> before = new ArrayList<>(snapshot);
        ((Collection<Object>) arguments[i]).add("changed");
        assertEquals(before, new ArrayList<>(snapshot), components[i].getName());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("changed"));
      }
    }
  }

  private static TypePolicy tokenPolicy() {
    return new TypePolicy(
        "dni.pe",
        1,
        "identity",
        Set.of("payments"),
        Set.of("support"),
        Duration.ofDays(1),
        Set.of(),
        null);
  }

  private static Object validArgument(Class<?> owner, RecordComponent component) {
    String name = component.getName();
    if (owner == TypePolicy.class) {
      return switch (name) {
        case "profile" -> "dni.pe";
        case "keyset" -> "identity";
        case "version" -> 1;
        case "domains" -> new LinkedHashSet<>(Set.of("payments"));
        case "purposes" -> new LinkedHashSet<>(Set.of("support"));
        case "retention" -> Duration.ofDays(1);
        default -> emptyArgument(component.getType());
      };
    }
    if (owner == AxizTokenizationVaultProperties.class) {
      if (name.equals("auth"))
        return new AxizTokenizationVaultProperties.Auth(
            null, "tenant", "client_id", "domains", "SCOPE_tokens:");
      if (name.equals("types")) return new LinkedHashMap<>(Map.of("DNI", tokenPolicy()));
    }
    if (owner == AxizSecurityProperties.Issuer.class && name.equals("profile"))
      return JwtAccessProfile.COMPATIBLE;
    if (owner == AxizSecurityProperties.Rule.class) {
      return switch (name) {
        case "name" -> "quota";
        case "key" -> "client";
        case "limit" -> 1L;
        case "window" -> Duration.ofSeconds(1);
        default -> emptyArgument(component.getType());
      };
    }
    if (owner == AxizCryptoKmsProperties.class) {
      if (name.equals("dataKeyMaxAge")) return Duration.ofSeconds(1);
      if (name.equals("dataKeyMaxUses")) return 1L;
    }
    return emptyArgument(component.getType());
  }

  private static Object emptyArgument(Class<?> type) {
    if (type == List.class) {
      return new ArrayList<>();
    }
    if (type == Set.class) {
      return new LinkedHashSet<>();
    }
    if (type == Map.class) {
      return new LinkedHashMap<>();
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == double.class) {
      return 0.0;
    }
    return null;
  }

  @Test
  void nestedFeatureFlagRulesCannotChangeAfterConfiguration() {
    Set<String> countries = new LinkedHashSet<>(Set.of("PE"));
    Map<String, Set<String>> rules = new LinkedHashMap<>();
    rules.put("country", countries);
    var flag = new AxizFeatureFlagsProperties.Flag(true, 100, Set.of(), Set.of(), rules, null);
    countries.add("OTHER");
    rules.clear();
    assertEquals(Map.of("country", Set.of("PE")), flag.rules());
    assertThrows(
        UnsupportedOperationException.class, () -> flag.rules().get("country").add("OTHER"));
  }
}
