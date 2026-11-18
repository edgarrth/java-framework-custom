package pe.axiz.core.crypto;

/** Trusted tenant and domain supplied by the application, never taken from an encrypted record. */
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
