package pe.axiz.core.tokenization.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import pe.axiz.core.crypto.IndexScope;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;

/** Length-framed scope; same bytes are used for persistence keys and authenticated binding. */
public record VaultScope(
    TokenType type, String tenant, TokenDomain domain, String profileId, int canonicalVersion) {
  public VaultScope {
    Objects.requireNonNull(type);
    Objects.requireNonNull(domain);
    tenant = VaultIdentifiers.identifier(tenant, 128);
    profileId = VaultIdentifiers.identifier(profileId, 64);
    if (canonicalVersion < 1) throw TokenErrors.failure(TokenErrors.INVALID);
  }

  public String encoded() {
    byte[][] parts = {
      type.value().getBytes(StandardCharsets.US_ASCII),
      tenant.getBytes(StandardCharsets.US_ASCII),
      domain.value().getBytes(StandardCharsets.US_ASCII),
      profileId.getBytes(StandardCharsets.US_ASCII)
    };
    int size = 5;
    for (byte[] part : parts) size += 4 + part.length;
    ByteBuffer framed = ByteBuffer.allocate(size).put((byte) 1).putInt(canonicalVersion);
    for (byte[] part : parts) framed.putInt(part.length).put(part);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(framed.array());
  }

  public IndexScope indexScope() {
    return new IndexScope(
        "token-vault", type.value(), tenant, domain.value(), profileId, canonicalVersion);
  }

  public String binding(Token token) {
    return "v1." + encoded() + "." + token.reveal();
  }

  @Override
  public String toString() {
    return "VaultScope[" + type.value() + ", " + profileId + ", " + canonicalVersion + "]";
  }
}
