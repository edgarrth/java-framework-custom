package pe.axiz.core.crypto;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Explicit, versioned search equivalence. Encryption always preserves the original. */
public record FieldProtectionPolicy(
    String purpose,
    String fieldType,
    String profileId,
    int canonicalVersion,
    UnaryOperator<String> canonicalizer) {
  public FieldProtectionPolicy {
    FieldScope.require(purpose);
    FieldScope.require(fieldType);
    FieldScope.require(profileId);
    if (canonicalVersion < 1)
      throw new IllegalArgumentException("Canonical version must be positive");
    Objects.requireNonNull(canonicalizer);
  }

  public static FieldProtectionPolicy identity(String purpose, String fieldType) {
    return new FieldProtectionPolicy(purpose, fieldType, "identity", 1, UnaryOperator.identity());
  }

  /** Peruvian DNI: eight ASCII digits, including leading zeroes; no implicit normalization. */
  public static FieldProtectionPolicy dniPe(String purpose) {
    return new FieldProtectionPolicy(
        purpose,
        "DNI_PE",
        "dni-pe",
        1,
        value -> {
          if (!value.matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid DNI format");
          return value;
        });
  }
}
