package pe.axiz.codegen.model;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** The explicit architecture scopes implied by the resolved capability graph. */
public record ArchitectureScopes(String cardData, String tokenization) {
  public static ArchitectureScopes of(Collection<Capability> capabilities) {
    Set<String> names = capabilities.stream().map(Capability::name).collect(Collectors.toSet());
    boolean vault = names.contains("tokenization-vault");
    return new ArchitectureScopes(
        names.contains("tokenization-pan") ? "VAULT" : names.contains("carddata") ? "EDGE" : "NONE",
        vault ? "VAULT" : names.contains("tokenization") ? "CLIENT" : "NONE");
  }
}
