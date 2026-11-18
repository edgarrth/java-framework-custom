package pe.axiz.spring.tokenization.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VaultPolicyBoundsTest {
  private static AxizTokenizationVaultProperties.TypePolicy.Builder policy() {
    return AxizTokenizationVaultProperties.TypePolicy.builder()
        .profile("dni.pe")
        .version(1)
        .keyset("identity")
        .domains(Set.of("billing"))
        .purposes(Set.of("customer-check"))
        .retention(Duration.ofDays(30))
        .metadataKeys(Set.of("reference"));
  }

  private static Set<String> identifiers(int count) {
    return IntStream.range(0, count).mapToObj(i -> "id-" + i).collect(Collectors.toSet());
  }

  @Test
  void invalidOrUnboundedTypePoliciesFailBeforeAcceptingSensitiveValues() {
    List<Consumer<AxizTokenizationVaultProperties.TypePolicy.Builder>> invalid =
        List.of(
            b -> b.version(0),
            b -> b.profile(""),
            b -> b.keyset("key\nother"),
            b -> b.domains(Set.of()),
            b -> b.domains(identifiers(65)),
            b -> b.domains(Set.of("a/b")),
            b -> b.purposes(Set.of()),
            b -> b.purposes(identifiers(33)),
            b -> b.purposes(Set.of("a b")),
            b -> b.retention(Duration.ZERO),
            b -> b.retention(Duration.ofDays(-1)),
            b -> b.retention(Duration.ofDays(3651)),
            b -> b.metadataKeys(identifiers(17)),
            b -> b.metadataKeys(Set.of("a".repeat(33))));
    for (var mutation : invalid) {
      var builder = policy();
      mutation.accept(builder);
      assertThrows(IllegalArgumentException.class, builder::build);
    }
    assertEquals(
        Duration.ofDays(3650), policy().retention(Duration.ofDays(3650)).build().retention());
  }

  @Test
  void policyCollectionsAreSnapshotsAndCannotBeMutatedAfterConfiguration() {
    var domains = new HashSet<>(Set.of("billing"));
    var configured = policy().domains(domains).build();
    domains.add("unauthorized-domain");
    assertEquals(Set.of("billing"), configured.domains());
    assertThrows(UnsupportedOperationException.class, () -> configured.domains().add("extra"));
    var auth =
        new AxizTokenizationVaultProperties.Auth(
            null, "tenant-a", "client_id", "domains", "SCOPE_tokens:");
    var types = new HashMap<>(Map.of("DNI", configured));
    var properties = new AxizTokenizationVaultProperties(auth, types);
    types.clear();
    assertEquals(Set.of("DNI"), properties.types().keySet());
    assertThrows(UnsupportedOperationException.class, () -> properties.types().clear());
    assertThrows(
        IllegalArgumentException.class, () -> new AxizTokenizationVaultProperties(auth, Map.of()));
    var tooMany = new HashMap<String, AxizTokenizationVaultProperties.TypePolicy>();
    IntStream.range(0, 65).forEach(i -> tooMany.put("TYPE" + i, configured));
    assertThrows(
        IllegalArgumentException.class, () -> new AxizTokenizationVaultProperties(auth, tooMany));
  }

  @Test
  void tenantSelectionMustBeExplicitAndClaimsCannotContainControlCharacters() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                null, null, "client_id", "domains", "SCOPE_tokens:"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                "tenant", "fixed", "client_id", "domains", "SCOPE_tokens:"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                "tenant", null, "client\nid", "domains", "SCOPE_tokens:"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AxizTokenizationVaultProperties.Auth(
                "tenant", null, "c".repeat(65), "domains", "SCOPE_tokens:"));
  }
}
