package pe.axiz.core.tokenization.vault;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.vault.TokenAuditEntry.Result;
import pe.axiz.core.tokenization.vault.VaultPolicy.Fingerprints;
import pe.axiz.core.tokenization.vault.VaultPolicy.Request;

/** Synchronous vault protocol. No value or token result escapes before confirmed audit. */
public final class VaultTokenizer implements VaultOperations {
  private final VaultPolicy policy;
  private final TokenVault vault;
  private final EnvelopeEncryptor encryption;
  private final TokenGenerator tokens;
  private final TokenBudget budget;
  private final TokenizationAudit audit;
  private final TokenReconciliationLog reconciliation;
  private final TokenReconciliationAlarm alarm;
  private final Supplier<UUID> operationIds;

  private VaultTokenizer(Builder builder) {
    VaultPolicy policy = Objects.requireNonNull(builder.policy, "policy");
    TokenVault vault = Objects.requireNonNull(builder.vault, "vault");
    EnvelopeEncryptor encryption = Objects.requireNonNull(builder.encryption, "encryption");
    TokenGenerator tokens = Objects.requireNonNull(builder.tokens, "tokens");
    TokenBudget budget = Objects.requireNonNull(builder.budget, "budget");
    TokenizationAudit audit = Objects.requireNonNull(builder.audit, "audit");
    TokenReconciliationLog reconciliation =
        Objects.requireNonNull(builder.reconciliation, "reconciliation");
    TokenReconciliationAlarm alarm = Objects.requireNonNull(builder.alarm, "alarm");
    Supplier<UUID> operationIds = Objects.requireNonNull(builder.operationIds, "operationIds");
    this.policy = policy;
    this.vault = vault;
    this.encryption = encryption;
    this.tokens = tokens;
    this.budget = budget;
    this.audit = audit;
    this.reconciliation = reconciliation;
    this.alarm = alarm;
    this.operationIds = operationIds;
  }

  @Override
  public TokenizationResult tokenize(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String operationId) {
    return run(
        caller,
        TokenOperation.TOKENIZE,
        type,
        domain,
        operationId,
        null,
        request -> {
          try (SensitiveValue canonical = policy.canonicalize(request, value)) {
            for (int attempt = 0; attempt < 3; attempt++) {
              FingerprintKeyState state = vault.keyState(request.policy().keysetId());
              Fingerprints fingerprints = policy.fingerprints(request, canonical, state);
              Optional<VaultRecord> found =
                  vault.findByFingerprint(request.scope(), fingerprints.all());
              if (found.isPresent()) {
                VaultRecord existing = policy.checked(request, found.get(), fingerprints.all());
                if (!policy.expired(existing)) return result(request, existing, false);
                // TTL alone cannot delete unique references. A conditional, coordinated removal is
                // required.
                budget.beforeWrite(caller, request.scope());
                deleteRecord(request, existing);
                continue;
              }
              budget.beforeWrite(caller, request.scope());
              Token token = tokens.next();
              Map<String, String> metadata = policy.metadata(request, canonical);
              byte[] plaintext = VaultText.encode(canonical);
              VaultRecord record;
              try {
                record =
                    policy.record(
                        request,
                        token,
                        fingerprints.write(),
                        encryption.encrypt(
                            plaintext,
                            VaultPolicy.ENCRYPTION_CONTEXT,
                            request.scope().binding(token)),
                        metadata);
              } finally {
                Arrays.fill(plaintext, (byte) 0);
              }
              PutOutcome outcome;
              try {
                outcome = vault.putIfAbsent(record, state, fingerprints.others());
              } catch (VaultUncertainException unknown) {
                throw uncertain(request, record, UncertainTokenOperation.Phase.CREATE);
              }
              switch (outcome) {
                case PutOutcome.Created ignored -> {
                  return result(request, record, true);
                }
                case PutOutcome.FingerprintExists winner -> {
                  Optional<VaultRecord> won = vault.find(request.scope(), winner.token());
                  if (won.isPresent()) {
                    VaultRecord checked = policy.checked(request, won.get(), fingerprints.all());
                    if (!checked.token().equals(winner.token()))
                      throw new VaultIntegrityException();
                    if (!policy.expired(checked)) return result(request, checked, false);
                    deleteRecord(request, checked);
                  }
                }
                case PutOutcome.TokenCollision ignored -> {
                  /* Generate again within the fixed budget. */
                }
                case PutOutcome.StaleKeyState ignored -> {
                  /* Reload the durable barrier before replanning. */
                }
              }
            }
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          }
        });
  }

  @Override
  public Optional<Token> lookup(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String purpose) {
    return run(
        caller,
        TokenOperation.LOOKUP,
        type,
        domain,
        null,
        purpose,
        request -> {
          try (SensitiveValue canonical = policy.canonicalize(request, value)) {
            FingerprintKeyState state = vault.keyState(request.policy().keysetId());
            Fingerprints fingerprints = policy.fingerprints(request, canonical, state);
            Optional<VaultRecord> found =
                vault
                    .findByFingerprint(request.scope(), fingerprints.all())
                    .map(record -> policy.checked(request, record, fingerprints.all()))
                    .filter(record -> !policy.expired(record));
            audit.publishConfirmed(
                policy.audit(
                    request,
                    found.isPresent() ? Result.FOUND : Result.ABSENT,
                    found.map(VaultRecord::token).orElse(null)));
            return found.map(VaultRecord::token);
          }
        });
  }

  @Override
  public SensitiveValue detokenize(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain, String purpose) {
    return run(
        caller,
        TokenOperation.DETOKENIZE,
        type,
        domain,
        null,
        purpose,
        request -> {
          VaultRecord record =
              visible(request, token).orElseThrow(() -> TokenErrors.failure(TokenErrors.NOT_FOUND));
          byte[] plaintext =
              encryption.decrypt(
                  record.ciphertext(),
                  VaultPolicy.ENCRYPTION_CONTEXT,
                  request.scope().binding(token));
          SensitiveValue revealed;
          try {
            revealed = VaultText.decode(plaintext);
          } finally {
            Arrays.fill(plaintext, (byte) 0);
          }
          try {
            audit.publishConfirmed(policy.audit(request, Result.REVEALED, token));
            return revealed;
          } catch (RuntimeException | Error failed) {
            revealed.close();
            throw failed;
          }
        });
  }

  @Override
  public Optional<TokenDescriptor> describe(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain) {
    return run(
        caller,
        TokenOperation.DESCRIBE,
        type,
        domain,
        null,
        null,
        request -> {
          Optional<VaultRecord> found = visible(request, token);
          audit.publishConfirmed(
              policy.audit(
                  request,
                  found.isPresent() ? Result.FOUND : Result.ABSENT,
                  found.isPresent() ? token : null));
          return found.map(record -> new TokenDescriptor(record.token(), type, record.metadata()));
        });
  }

  @Override
  public boolean delete(TokenCaller caller, TokenType type, Token token, TokenDomain domain) {
    return run(
        caller,
        TokenOperation.DELETE,
        type,
        domain,
        null,
        null,
        request -> {
          // Explicit deletion also removes logically expired rows and their index references.
          Optional<VaultRecord> found =
              vault
                  .find(request.scope(), token)
                  .map(record -> policy.checked(request, record, null));
          if (found.isPresent() && !found.get().token().equals(token))
            throw new VaultIntegrityException();
          boolean removed = found.isPresent() && deleteRecord(request, found.get());
          audit.publishConfirmed(
              policy.audit(
                  request,
                  removed ? Result.DELETED : Result.ABSENT,
                  found.isPresent() ? token : null));
          return removed;
        });
  }

  private Optional<VaultRecord> visible(Request request, Token token) {
    return vault
        .find(request.scope(), token)
        .map(
            record -> {
              if (!record.token().equals(token)) throw new VaultIntegrityException();
              return policy.checked(request, record, null);
            })
        .filter(record -> !policy.expired(record));
  }

  private boolean deleteRecord(Request request, VaultRecord record) {
    DeleteOutcome outcome;
    try {
      outcome = vault.delete(record);
    } catch (VaultUncertainException unknown) {
      throw uncertain(request, record, UncertainTokenOperation.Phase.DELETE);
    }
    return switch (outcome) {
      case DELETED -> true;
      case NOT_FOUND -> false;
      case CHANGED -> throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      case INTEGRITY_FAILURE -> throw new VaultIntegrityException();
    };
  }

  private TokenizationResult result(Request request, VaultRecord record, boolean created) {
    audit.publishConfirmed(
        policy.audit(request, created ? Result.CREATED : Result.FOUND, record.token()));
    return new TokenizationResult(
        record.token(), record.scope().type(), created, record.metadata());
  }

  private RuntimeException uncertain(
      Request request, VaultRecord record, UncertainTokenOperation.Phase phase) {
    var operation =
        new UncertainTokenOperation(
            request.operationId(),
            request.scope(),
            phase,
            record.token(),
            policy.clock().instant(),
            UncertainTokenOperation.Reason.STORAGE_ACK_UNKNOWN);
    try {
      reconciliation.recordConfirmed(operation);
    } catch (RuntimeException failure) {
      try {
        alarm.failed(operation);
      } catch (RuntimeException alertFailure) {
        /* Preserve the uncertain outcome even when alerting fails. */
      }
    }
    return TokenErrors.failure(TokenErrors.UNCERTAIN);
  }

  private <T> T run(
      TokenCaller caller,
      TokenOperation operation,
      TokenType type,
      TokenDomain domain,
      @Nullable String externalId,
      @Nullable String purpose,
      Function<Request, T> work) {
    UUID id = Objects.requireNonNull(operationIds.get());
    Request request = null;
    TokenBudget.Permit permit = null;
    T result = null;
    try {
      if (externalId != null) id = VaultIdentifiers.operationId(externalId);
      request = policy.authorize(caller, operation, type, domain, id, purpose);
      permit = budget.acquire(caller, operation, request.scope());
      result = work.apply(request);
      TokenBudget.Permit held = permit;
      permit = null;
      held.close();
      return result;
    } catch (RuntimeException failure) {
      if (result instanceof SensitiveValue sensitive) sensitive.close();
      var safe = VaultPolicy.safeFailure(failure);
      try {
        audit.publishConfirmed(
            TokenAuditEntry.of(
                operation,
                caller,
                type,
                domain,
                id,
                request == null ? null : request.purpose(),
                VaultPolicy.failureResult(failure),
                null,
                policy.clock().instant()));
      } catch (RuntimeException auditFailure) {
        if (!safe.code().equals(TokenErrors.UNCERTAIN.code()))
          throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      }
      throw safe;
    } catch (Error failure) {
      if (result instanceof SensitiveValue sensitive) sensitive.close();
      throw failure;
    } finally {
      if (permit != null) {
        try {
          permit.close();
        } catch (RuntimeException releaseFailure) {
          /* The operation is already failing; do not overwrite its outcome. */
        }
      }
    }
  }

  /** Named construction; required values and invariants are checked by build. */
  public static Builder builder() {
    return new Builder();
  }

  /** Mutable construction state; use one builder per operation, never as shared state. */
  public static final class Builder {
    private @Nullable VaultPolicy policy = null;
    private @Nullable TokenVault vault = null;
    private @Nullable EnvelopeEncryptor encryption = null;
    private @Nullable TokenGenerator tokens = null;
    private @Nullable TokenBudget budget = null;
    private @Nullable TokenizationAudit audit = null;
    private @Nullable TokenReconciliationLog reconciliation = null;
    private @Nullable TokenReconciliationAlarm alarm = null;
    private @Nullable Supplier<UUID> operationIds = null;

    private Builder() {}

    public Builder policy(VaultPolicy value) {
      this.policy = value;
      return this;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The vault is a borrowed persistence port; identity, transaction state and lifecycle must be shared.")
    public Builder vault(TokenVault value) {
      this.vault = value;
      return this;
    }

    public Builder encryption(EnvelopeEncryptor value) {
      this.encryption = value;
      return this;
    }

    public Builder tokens(TokenGenerator value) {
      this.tokens = value;
      return this;
    }

    public Builder budget(TokenBudget value) {
      this.budget = value;
      return this;
    }

    public Builder audit(TokenizationAudit value) {
      this.audit = value;
      return this;
    }

    public Builder reconciliation(TokenReconciliationLog value) {
      this.reconciliation = value;
      return this;
    }

    public Builder alarm(TokenReconciliationAlarm value) {
      this.alarm = value;
      return this;
    }

    public Builder operationIds(Supplier<UUID> value) {
      this.operationIds = value;
      return this;
    }

    public VaultTokenizer build() {
      return new VaultTokenizer(this);
    }
  }
}
