package pe.axiz.core.sensitive;

/**
 * Sensitivity metadata of a property.
 *
 * @param name property name
 * @param level sensitivity level
 * @param mask mask type
 */
public record SensitiveField(String name, SensitivityLevel level, MaskType mask) {

  public static SensitiveField of(String name, Sensitive s) {
    return new SensitiveField(name, s.level(), s.mask());
  }

  public static SensitiveField of(String name, Mask m) {
    return new SensitiveField(name, SensitivityLevel.MEDIUM, m.value());
  }
}
