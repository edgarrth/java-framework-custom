package pe.axiz.core.crypto;

/**
 * Tenant y dominio confiables suministrados por la aplicación, nunca tomados de un registro
 * cifrado.
 */
public record FieldScope(String tenant, String domain) {
  public FieldScope {
    require(tenant);
    require(domain);
  }

  static String require(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 128
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid field scope identifier");
    StrictUtf8.encode(value, 512);
    return value;
  }
}
