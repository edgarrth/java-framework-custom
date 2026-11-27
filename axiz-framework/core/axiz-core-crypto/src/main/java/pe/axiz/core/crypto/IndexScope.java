package pe.axiz.core.crypto;

/**
 * El alcance de búsqueda excluye la identidad de la fila y el nonce para poder construirse antes de
 * encontrar una fila.
 */
public record IndexScope(
    String purpose,
    String fieldType,
    String tenant,
    String domain,
    String profileId,
    int canonicalVersion) {
  public IndexScope {
    for (String part : new String[] {purpose, fieldType, tenant, domain, profileId}) {
      if (part == null || part.isBlank()) throw new CryptoException("Index scope must be explicit");
      StrictUtf8.encode(part, 1024);
    }
    if (canonicalVersion < 1) throw new CryptoException("Invalid canonicalization version");
  }
}
