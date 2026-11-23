package pe.axiz.core.tokenization.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;

/**
 * Evidencia de una lista de permitidos; sin valor original, índice, texto cifrado, credenciales ni
 * excepción arbitraria.
 */
public record TokenAuditEntry(
    TokenOperation operation,
    TokenCaller caller,
    TokenType type,
    TokenDomain domain,
    UUID operationId,
    @Nullable String purpose,
    Result result,
    String tokenReference,
    Instant at) {
  /**
   * Catálogo cerrado de resultados, con información de presencia solo después de la autorización.
   */
  public enum Result {
    CREATED,
    FOUND,
    ABSENT,
    REVEALED,
    DELETED,
    DENIED,
    INVALID,
    CANCELLED,
    RATE_LIMITED,
    UNAVAILABLE,
    UNCERTAIN,
    INTEGRITY_FAILURE
  }

  public TokenAuditEntry {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(caller);
    Objects.requireNonNull(type);
    Objects.requireNonNull(domain);
    Objects.requireNonNull(operationId);
    Objects.requireNonNull(result);
    Objects.requireNonNull(at);
    if (!tokenReference.equals("none") && !tokenReference.matches("tok_\\*{18}[0-9A-Za-z]{4}"))
      throw new IllegalArgumentException("Only a masked token reference is allowed in audit");
    if (purpose != null) purpose = VaultIdentifiers.identifier(purpose, 64);
  }

  /** Descarta el token completo antes de crear la instantánea de auditoría. */
  public static TokenAuditEntry of(
      TokenOperation operation,
      TokenCaller caller,
      TokenType type,
      TokenDomain domain,
      UUID operationId,
      @Nullable String purpose,
      Result result,
      @Nullable Token token,
      Instant at) {
    return TokenAuditEntry.builder()
        .operation(operation)
        .caller(caller)
        .type(type)
        .domain(domain)
        .operationId(operationId)
        .purpose(purpose)
        .result(result)
        .tokenReference(token == null ? "none" : token.masked())
        .at(at)
        .build();
  }

  @Override
  public String toString() {
    return "TokenAuditEntry[" + operation + ", " + result + "]";
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable TokenOperation operation = null;
    private @Nullable TokenCaller caller = null;
    private @Nullable TokenType type = null;
    private @Nullable TokenDomain domain = null;
    private @Nullable UUID operationId = null;
    private @Nullable String purpose = null;
    private @Nullable Result result = null;
    private @Nullable String tokenReference = null;
    private @Nullable Instant at = null;

    private Builder() {}

    public Builder operation(TokenOperation value) {
      this.operation = value;
      return this;
    }

    public Builder caller(TokenCaller value) {
      this.caller = value;
      return this;
    }

    public Builder type(TokenType value) {
      this.type = value;
      return this;
    }

    public Builder domain(TokenDomain value) {
      this.domain = value;
      return this;
    }

    public Builder operationId(UUID value) {
      this.operationId = value;
      return this;
    }

    public Builder purpose(@Nullable String value) {
      this.purpose = value;
      return this;
    }

    public Builder result(Result value) {
      this.result = value;
      return this;
    }

    public Builder tokenReference(String value) {
      this.tokenReference = value;
      return this;
    }

    public Builder at(Instant value) {
      this.at = value;
      return this;
    }

    public TokenAuditEntry build() {
      return new TokenAuditEntry(
          Objects.requireNonNull(operation, "operation"),
          Objects.requireNonNull(caller, "caller"),
          Objects.requireNonNull(type, "type"),
          Objects.requireNonNull(domain, "domain"),
          Objects.requireNonNull(operationId, "operationId"),
          purpose,
          Objects.requireNonNull(result, "result"),
          Objects.requireNonNull(tokenReference, "tokenReference"),
          Objects.requireNonNull(at, "at"));
    }
  }
}
