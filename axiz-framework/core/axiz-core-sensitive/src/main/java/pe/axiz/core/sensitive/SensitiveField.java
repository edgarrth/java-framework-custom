package pe.axiz.core.sensitive;

/**
 * Metadatos de sensibilidad de una propiedad.
 *
 * @param name nombre de la propiedad
 * @param level nivel de sensibilidad
 * @param mask tipo de máscara
 */
public record SensitiveField(String name, SensitivityLevel level, MaskType mask) {

  public static SensitiveField of(String name, Sensitive s) {
    return new SensitiveField(name, s.level(), s.mask());
  }

  public static SensitiveField of(String name, Mask m) {
    return new SensitiveField(name, SensitivityLevel.MEDIUM, m.value());
  }
}
