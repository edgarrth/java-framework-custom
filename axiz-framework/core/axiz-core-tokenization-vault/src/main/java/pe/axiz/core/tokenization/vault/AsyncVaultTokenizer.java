package pe.axiz.core.tokenization.vault;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.crypto.AsyncEnvelopeEncryptor;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenDomain;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenOperation;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.vault.AsyncTokenBudget.Permit;
import pe.axiz.core.tokenization.vault.TokenAuditEntry.Result;
import pe.axiz.core.tokenization.vault.VaultPolicy.Fingerprints;
import pe.axiz.core.tokenization.vault.VaultPolicy.Request;

/**
 * Protocolo asíncrono nativo de la bóveda, con identidad explícita y sin executor ni esperas
 * bloqueantes. La cancelación impide la siguiente operación; la E/S aceptada se resuelve bajo los
 * plazos finitos obligatorios del proveedor antes de liberar su lease y los valores propios. En
 * particular, la cancelación nunca disfraza una escritura aceptada como un rollback confirmado.
 */
public final class AsyncVaultTokenizer implements AsyncVaultOperations {
  private final VaultPolicy policy;
  private final AsyncTokenVault vault;
  private final AsyncEnvelopeEncryptor encryption;
  private final TokenGenerator tokens;
  private final AsyncTokenBudget budget;
  private final AsyncTokenizationAudit audit;
  private final AsyncTokenReconciliationLog reconciliation;
  private final TokenReconciliationAlarm alarm;
  private final Supplier<UUID> operationIds;

  private AsyncVaultTokenizer(Builder builder) {
    VaultPolicy policy = Objects.requireNonNull(builder.policy, "policy");
    AsyncTokenVault vault = Objects.requireNonNull(builder.vault, "vault");
    AsyncEnvelopeEncryptor encryption = Objects.requireNonNull(builder.encryption, "encryption");
    TokenGenerator tokens = Objects.requireNonNull(builder.tokens, "tokens");
    AsyncTokenBudget budget = Objects.requireNonNull(builder.budget, "budget");
    AsyncTokenizationAudit audit = Objects.requireNonNull(builder.audit, "audit");
    AsyncTokenReconciliationLog reconciliation =
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
  public CompletionStage<TokenizationResult> tokenize(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String operationId) {
    var flow =
        new Invocation<TokenizationResult>(caller, TokenOperation.TOKENIZE, type, domain, null);
    flow.start(
        operationId,
        value,
        () -> {
          flow.canonicalize();
          tokenize(flow, 0);
        });
    return flow.result;
  }

  private void tokenize(Invocation<TokenizationResult> flow, int attempt) {
    flow.active();
    if (attempt >= 3) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    Request request = flow.request();
    flow.await(
        () -> vault.keyState(request.policy().keysetId()),
        state -> {
          flow.active();
          Fingerprints fingerprints = policy.fingerprints(request, flow.canonical(), state);
          flow.await(
              () -> vault.findByFingerprint(request.scope(), fingerprints.all()),
              found -> {
                flow.active();
                if (found.isPresent()) {
                  VaultRecord existing = policy.checked(request, found.get(), fingerprints.all());
                  authenticate(
                      flow,
                      existing,
                      () -> {
                        if (!policy.expired(existing)) {
                          tokenResult(flow, existing, false);
                          return;
                        }
                        flow.await(
                            () -> budget.beforeWrite(request.caller(), request.scope()),
                            ignored -> {
                              flow.active();
                              deleteRecord(flow, existing, removed -> tokenize(flow, attempt + 1));
                            });
                      });
                  return;
                }
                flow.await(
                    () -> budget.beforeWrite(request.caller(), request.scope()),
                    ignored -> {
                      flow.active();
                      Token token = tokens.next();
                      Map<String, String> metadata = policy.metadata(request, flow.canonical());
                      flow.await(
                          () -> {
                            byte[] plaintext = VaultText.encode(flow.canonical());
                            try {
                              return encryption.encrypt(
                                  plaintext,
                                  VaultPolicy.ENCRYPTION_CONTEXT,
                                  request.scope().binding(token));
                            } finally {
                              Arrays.fill(plaintext, (byte) 0);
                            }
                          },
                          encrypted -> {
                            flow.active();
                            VaultRecord record =
                                policy.record(
                                    request, token, fingerprints.write(), encrypted, metadata);
                            flow.await(
                                () -> vault.putIfAbsent(record, state, fingerprints.others()),
                                outcome -> {
                                  switch (outcome) {
                                    case PutOutcome.Created created ->
                                        tokenResult(flow, record, true);
                                    case PutOutcome.FingerprintExists winner -> {
                                      flow.active();
                                      flow.await(
                                          () -> vault.find(request.scope(), winner.token()),
                                          won -> {
                                            flow.active();
                                            if (won.isPresent()) {
                                              VaultRecord checked =
                                                  policy.checked(
                                                      request, won.get(), fingerprints.all());
                                              if (!checked.token().equals(winner.token()))
                                                throw new VaultIntegrityException();
                                              authenticate(
                                                  flow,
                                                  checked,
                                                  () -> {
                                                    if (!policy.expired(checked)) {
                                                      tokenResult(flow, checked, false);
                                                      return;
                                                    }
                                                    deleteRecord(
                                                        flow,
                                                        checked,
                                                        removed -> tokenize(flow, attempt + 1));
                                                  });
                                            } else tokenize(flow, attempt + 1);
                                          });
                                    }
                                    case PutOutcome.TokenCollision collision ->
                                        tokenize(flow, attempt + 1);
                                    case PutOutcome.StaleKeyState stale ->
                                        tokenize(flow, attempt + 1);
                                  }
                                },
                                failure ->
                                    mutationFailure(
                                        flow,
                                        record,
                                        UncertainTokenOperation.Phase.CREATE,
                                        failure));
                          });
                    });
              });
        });
  }

  private void authenticate(Invocation<?> flow, VaultRecord record, Runnable next) {
    flow.await(
        () ->
            encryption.decrypt(
                record.ciphertext(),
                VaultPolicy.ENCRYPTION_CONTEXT,
                record.scope().binding(record.token())),
        plaintext -> {
          try {
            flow.active();
            policy.authenticate(flow.request(), record, plaintext, flow.canonical);
          } finally {
            Arrays.fill(plaintext, (byte) 0);
          }
          next.run();
        });
  }

  private void tokenResult(
      Invocation<TokenizationResult> flow, VaultRecord record, boolean created) {
    // Incluso cuando el solicitante canceló, una escritura confirmada se audita igualmente.
    flow.confirm(
        created ? Result.CREATED : Result.FOUND,
        record.token(),
        () ->
            flow.finish(
                new TokenizationResult(
                    record.token(), record.scope().type(), created, record.metadata()),
                null));
  }

  @Override
  public CompletionStage<Optional<Token>> lookup(
      TokenCaller caller,
      TokenType type,
      SensitiveValue value,
      TokenDomain domain,
      String purpose) {
    var flow =
        new Invocation<Optional<Token>>(caller, TokenOperation.LOOKUP, type, domain, purpose);
    flow.start(
        null,
        value,
        () -> {
          flow.canonicalize();
          Request request = flow.request();
          flow.await(
              () -> vault.keyState(request.policy().keysetId()),
              state -> {
                flow.active();
                Fingerprints fingerprints = policy.fingerprints(request, flow.canonical(), state);
                flow.await(
                    () -> vault.findByFingerprint(request.scope(), fingerprints.all()),
                    found -> {
                      flow.active();
                      if (found.isEmpty()) {
                        flow.confirm(
                            Result.ABSENT, null, () -> flow.finish(Optional.empty(), null));
                        return;
                      }
                      VaultRecord record = policy.checked(request, found.get(), fingerprints.all());
                      authenticate(
                          flow,
                          record,
                          () -> {
                            Optional<Token> token =
                                policy.expired(record)
                                    ? Optional.empty()
                                    : Optional.of(record.token());
                            flow.confirm(
                                token.isPresent() ? Result.FOUND : Result.ABSENT,
                                token.orElse(null),
                                () -> flow.finish(token, null));
                          });
                    });
              });
        });
    return flow.result;
  }

  @Override
  public CompletionStage<SensitiveValue> detokenize(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain, String purpose) {
    var flow =
        new Invocation<SensitiveValue>(caller, TokenOperation.DETOKENIZE, type, domain, purpose);
    flow.start(
        null,
        null,
        () -> {
          Request request = flow.request();
          flow.await(
              () -> vault.find(request.scope(), token),
              found -> {
                flow.active();
                VaultRecord record =
                    visible(request, token, found)
                        .orElseThrow(() -> TokenErrors.failure(TokenErrors.NOT_FOUND));
                flow.await(
                    () ->
                        encryption.decrypt(
                            record.ciphertext(),
                            VaultPolicy.ENCRYPTION_CONTEXT,
                            request.scope().binding(token)),
                    plaintext -> {
                      try {
                        flow.active();
                        policy.authenticate(request, record, plaintext, null);
                        flow.revealed = VaultText.decode(plaintext);
                      } finally {
                        Arrays.fill(plaintext, (byte) 0);
                      }
                      flow.confirm(
                          Result.REVEALED,
                          token,
                          () -> flow.finish(Objects.requireNonNull(flow.revealed), null));
                    });
              });
        });
    return flow.result;
  }

  @Override
  public CompletionStage<Optional<TokenDescriptor>> describe(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain) {
    var flow =
        new Invocation<Optional<TokenDescriptor>>(
            caller, TokenOperation.DESCRIBE, type, domain, null);
    flow.start(
        null,
        null,
        () -> {
          Request request = flow.request();
          flow.await(
              () -> vault.find(request.scope(), token),
              found -> {
                flow.active();
                Optional<VaultRecord> visible = visible(request, token, found);
                if (visible.isEmpty()) {
                  flow.confirm(Result.ABSENT, null, () -> flow.finish(Optional.empty(), null));
                  return;
                }
                VaultRecord record = visible.get();
                authenticate(
                    flow,
                    record,
                    () ->
                        flow.confirm(
                            Result.FOUND,
                            token,
                            () ->
                                flow.finish(
                                    Optional.of(
                                        new TokenDescriptor(token, type, record.metadata())),
                                    null)));
              });
        });
    return flow.result;
  }

  @Override
  public CompletionStage<Boolean> delete(
      TokenCaller caller, TokenType type, Token token, TokenDomain domain) {
    var flow = new Invocation<Boolean>(caller, TokenOperation.DELETE, type, domain, null);
    flow.start(
        null,
        null,
        () -> {
          Request request = flow.request();
          flow.await(
              () -> vault.find(request.scope(), token),
              found -> {
                flow.active();
                if (found.isEmpty()) {
                  flow.confirm(Result.ABSENT, null, () -> flow.finish(false, null));
                  return;
                }
                VaultRecord record = policy.checked(request, found.get(), null);
                if (!record.token().equals(token)) throw new VaultIntegrityException();
                deleteRecord(
                    flow,
                    record,
                    removed ->
                        flow.confirm(
                            removed ? Result.DELETED : Result.ABSENT,
                            token,
                            () -> flow.finish(removed, null)));
              });
        });
    return flow.result;
  }

  private Optional<VaultRecord> visible(Request request, Token token, Optional<VaultRecord> found) {
    return found
        .map(
            record -> {
              if (!record.token().equals(token)) throw new VaultIntegrityException();
              return policy.checked(request, record, null);
            })
        .filter(record -> !policy.expired(record));
  }

  private <T> void deleteRecord(Invocation<T> flow, VaultRecord record, Consumer<Boolean> next) {
    flow.active();
    flow.await(
        () -> vault.delete(record),
        outcome -> {
          boolean removed =
              switch (outcome) {
                case DELETED -> true;
                case NOT_FOUND -> false;
                case CHANGED -> throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
                case INTEGRITY_FAILURE -> throw new VaultIntegrityException();
              };
          next.accept(removed);
        },
        failure -> mutationFailure(flow, record, UncertainTokenOperation.Phase.DELETE, failure));
  }

  private <T> void mutationFailure(
      Invocation<T> flow,
      VaultRecord record,
      UncertainTokenOperation.Phase phase,
      Throwable failure) {
    if (!(Stages.unwrap(failure) instanceof VaultUncertainException)) {
      flow.fail(failure);
      return;
    }
    var operation =
        new UncertainTokenOperation(
            flow.id,
            record.scope(),
            phase,
            record.token(),
            policy.clock().instant(),
            UncertainTokenOperation.Reason.STORAGE_ACK_UNKNOWN);
    flow.await(
        () -> reconciliation.recordConfirmed(operation),
        ignored -> flow.fail(TokenErrors.failure(TokenErrors.UNCERTAIN)),
        logFailure -> {
          try {
            alarm.failed(operation);
          } catch (RuntimeException alertFailure) {
            /* El resultado incierto sigue siendo autoritativo. */
          }
          flow.fail(TokenErrors.failure(TokenErrors.UNCERTAIN));
        });
  }

  /**
   * Un único protocolo secuencial es dueño de todos los recursos sensibles hasta que se resuelven
   * la E/S y la liberación del lease.
   */
  private final class Invocation<T> {
    private final TokenCaller caller;
    private final TokenOperation operation;
    private final TokenType type;
    private final TokenDomain domain;
    private final @Nullable String purpose;
    private UUID id;
    private @Nullable Request checked;
    private @Nullable SensitiveValue input;
    private @Nullable SensitiveValue canonical;
    private @Nullable SensitiveValue revealed;
    private @Nullable Permit permit;
    private boolean finishing;
    private final CompletableFuture<T> result = new CompletableFuture<>();

    Invocation(
        TokenCaller caller,
        TokenOperation operation,
        TokenType type,
        TokenDomain domain,
        @Nullable String purpose) {
      this.caller = caller;
      this.operation = operation;
      this.type = type;
      this.domain = domain;
      this.purpose = purpose;
      id = Objects.requireNonNull(operationIds.get());
    }

    void start(@Nullable String externalId, @Nullable SensitiveValue value, Runnable begin) {
      try {
        if (externalId != null) id = VaultIdentifiers.operationId(externalId);
        checked = policy.authorize(caller, operation, type, domain, id, purpose);
        if (value != null) input = value.copy();
        await(
            () -> budget.acquire(caller, operation, request().scope(), request().operationId()),
            acquired -> {
              permit = Objects.requireNonNull(acquired);
              active();
              begin.run();
            });
      } catch (RuntimeException | Error failure) {
        fail(failure);
      }
    }

    Request request() {
      return Objects.requireNonNull(checked);
    }

    SensitiveValue canonical() {
      return Objects.requireNonNull(canonical);
    }

    void canonicalize() {
      active();
      canonical = policy.canonicalize(request(), Objects.requireNonNull(input));
    }

    void active() {
      if (result.isCancelled()) throw new CancellationException("Token operation cancelled");
    }

    void confirm(Result outcome, @Nullable Token token, Runnable next) {
      await(
          () -> audit.publishConfirmed(policy.audit(request(), outcome, token)),
          ignored -> next.run(),
          failed -> {
            if (outcome != Result.CREATED && outcome != Result.DELETED) {
              fail(TokenErrors.failure(TokenErrors.UNAVAILABLE));
              return;
            }
            var evidence =
                new UncertainTokenOperation(
                    id,
                    request().scope(),
                    outcome == Result.CREATED
                        ? UncertainTokenOperation.Phase.CREATE
                        : UncertainTokenOperation.Phase.DELETE,
                    token,
                    policy.clock().instant(),
                    UncertainTokenOperation.Reason.COMMITTED_AUDIT_UNCONFIRMED);
            await(
                () -> reconciliation.recordConfirmed(evidence),
                ignored -> fail(TokenErrors.failure(TokenErrors.UNCERTAIN)),
                logFailure -> {
                  try {
                    alarm.failed(evidence);
                  } catch (RuntimeException alertFailure) {
                    /* Se preserva el resultado con commit. */
                  }
                  fail(TokenErrors.failure(TokenErrors.UNCERTAIN));
                });
          });
    }

    <V> void await(Supplier<CompletionStage<V>> work, Consumer<V> next) {
      await(work, next, this::fail);
    }

    @SuppressWarnings(
        "FutureReturnValueIgnored") // Cada fallo de callback se encamina a la limpieza
    // terminal de este protocolo.
    <V> void await(
        Supplier<CompletionStage<V>> work, Consumer<V> next, Consumer<Throwable> failure) {
      // La identidad es un argumento explícito inmutable. Aquí no se consulta ningún ThreadLocal
      // ambiental.
      try {
        Objects.requireNonNull(work.get())
            .whenComplete(
                (answer, error) -> {
                  try {
                    if (error == null) next.accept(answer);
                    else failure.accept(Stages.unwrap(error));
                  } catch (RuntimeException | Error callbackFailure) {
                    fail(callbackFailure);
                  }
                });
      } catch (RuntimeException | Error submissionFailure) {
        failure.accept(submissionFailure);
      }
    }

    void fail(Throwable failure) {
      if (finishing) return;
      finishing = true;
      Throwable unwrapped = Stages.unwrap(failure);
      RuntimeException error =
          unwrapped instanceof RuntimeException runtime
              ? runtime
              : TokenErrors.failure(TokenErrors.UNAVAILABLE);
      RuntimeException safe = VaultPolicy.safeFailure(error);
      Result outcome =
          error instanceof CancellationException
              ? Result.CANCELLED
              : VaultPolicy.failureResult(error);
      TokenAuditEntry entry =
          TokenAuditEntry.of(
              operation,
              caller,
              type,
              domain,
              id,
              checked == null ? null : checked.purpose(),
              outcome,
              null,
              policy.clock().instant());
      await(
          () -> audit.publishConfirmed(entry),
          ignored -> finish(null, safe),
          auditFailure ->
              finish(
                  null,
                  safe instanceof AxizException axiz
                          && axiz.code().equals(TokenErrors.UNCERTAIN.code())
                      ? safe
                      : TokenErrors.failure(TokenErrors.UNAVAILABLE)));
    }

    void finish(@Nullable T answer, @Nullable Throwable failure) {
      finishing = true;
      Permit acquired = permit;
      permit = null;
      if (acquired == null) dispose(answer, failure);
      else
        await(
            acquired::release,
            ignored -> dispose(answer, failure),
            releaseFailure -> {
              LoggerFactory.getLogger(AsyncVaultTokenizer.class)
                  .warn(
                      "Token budget cleanup failed; operationId={}, failureType={}",
                      id,
                      releaseFailure.getClass().getSimpleName());
              dispose(answer, failure);
            });
    }

    void dispose(@Nullable T answer, @Nullable Throwable failure) {
      if (input != null) input.close();
      if (canonical != null) canonical.close();
      if (failure != null) result.completeExceptionally(failure);
      else if (answer != null && result.complete(answer)) {
        if (answer == revealed) revealed = null; // La propiedad se transfiere solo al entregar.
      } else if (answer == null)
        result.completeExceptionally(TokenErrors.failure(TokenErrors.UNAVAILABLE));
      if (revealed != null) revealed.close();
    }
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable VaultPolicy policy = null;
    private @Nullable AsyncTokenVault vault = null;
    private @Nullable AsyncEnvelopeEncryptor encryption = null;
    private @Nullable TokenGenerator tokens = null;
    private @Nullable AsyncTokenBudget budget = null;
    private @Nullable AsyncTokenizationAudit audit = null;
    private @Nullable AsyncTokenReconciliationLog reconciliation = null;
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
    public Builder vault(AsyncTokenVault value) {
      this.vault = value;
      return this;
    }

    public Builder encryption(AsyncEnvelopeEncryptor value) {
      this.encryption = value;
      return this;
    }

    public Builder tokens(TokenGenerator value) {
      this.tokens = value;
      return this;
    }

    public Builder budget(AsyncTokenBudget value) {
      this.budget = value;
      return this;
    }

    public Builder audit(AsyncTokenizationAudit value) {
      this.audit = value;
      return this;
    }

    public Builder reconciliation(AsyncTokenReconciliationLog value) {
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

    public AsyncVaultTokenizer build() {
      return new AsyncVaultTokenizer(this);
    }
  }
}
