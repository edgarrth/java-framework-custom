package pe.axiz.core.tokenization.vault;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Action;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Result;

/** Shared authorization, confirmed audit and uncertainty handling for privileged vault jobs. */
final class VaultMaintenanceExecution {
  /** Preserves classification through nested jobs without retaining the provider failure. */
  private static final class IntegrityFailure extends BusinessException {
    IntegrityFailure() {
      super(TokenErrors.UNAVAILABLE, "Vault maintenance integrity verification failed");
    }
  }

  private final VaultMaintenanceAccess access;
  private final VaultMaintenanceAudit audit;
  private final VaultMaintenanceReconciliation reconciliation;
  private final VaultMaintenanceAlarm alarm;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final String authority;

  VaultMaintenanceExecution(
      VaultMaintenanceAccess access,
      VaultMaintenanceAudit audit,
      VaultMaintenanceReconciliation reconciliation,
      VaultMaintenanceAlarm alarm,
      Clock clock,
      Supplier<UUID> ids,
      String authority) {
    this.access = Objects.requireNonNull(access);
    this.audit = Objects.requireNonNull(audit);
    this.reconciliation = Objects.requireNonNull(reconciliation);
    this.alarm = Objects.requireNonNull(alarm);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
    this.authority = VaultIdentifiers.identifier(authority, 128);
  }

  void authorize(
      TokenCaller caller, Action action, FingerprintKeyState state, @Nullable VaultRecord row) {
    authorize(caller, action, state.keysetId(), row);
  }

  void authorize(TokenCaller caller, Action action, String keyset, @Nullable VaultRecord row) {
    if (!caller.authorities().contains(authority)) throw TokenErrors.failure(TokenErrors.DENIED);
    // A separate mandatory policy must grant cross-tenant administration explicitly.
    access.check(caller, action, keyset, row == null ? null : row.scope());
  }

  private record ControlRef(String keysetId, long version) {}

  <T> T run(
      TokenCaller caller,
      Action action,
      FingerprintKeyState state,
      @Nullable VaultRecord row,
      Supplier<T> work) {
    return run(caller, action, new ControlRef(state.keysetId(), state.version()), row, work);
  }

  <T> T run(
      TokenCaller caller,
      Action action,
      WrappingKeyState state,
      @Nullable VaultRecord row,
      Supplier<T> work) {
    return run(caller, action, new ControlRef(state.keysetId(), state.version()), row, work);
  }

  <T> T run(
      TokenCaller caller,
      Action action,
      ControlRef state,
      @Nullable VaultRecord row,
      Supplier<T> work) {
    UUID id = Objects.requireNonNull(ids.get());
    Result confirmed = null;
    try {
      authorize(caller, action, state.keysetId(), row);
      audit.publishConfirmed(event(caller, action, state, row, id, Result.STARTED));
      T result = work.get();
      boolean conflict =
          Boolean.FALSE.equals(result)
              || result == MigrationOutcome.CHANGED
              || result == DeleteOutcome.CHANGED;
      confirmed = conflict ? Result.CONFLICT : Result.SUCCESS;
      audit.publishConfirmed(event(caller, action, state, row, id, confirmed));
      return result;
    } catch (RuntimeException failure) {
      boolean uncertain =
          confirmed != null
              || failure instanceof VaultUncertainException
              || VaultPolicy.safeFailure(failure).code().equals(TokenErrors.UNCERTAIN.code());
      if (confirmed != null || failure instanceof VaultUncertainException) {
        var operation =
            new UncertainVaultMaintenance(
                event(
                    caller,
                    action,
                    state,
                    row,
                    id,
                    confirmed == null ? Result.UNCERTAIN : confirmed),
                row == null ? null : row.token());
        try {
          reconciliation.recordConfirmed(operation);
        } catch (RuntimeException logFailure) {
          try {
            alarm.failed(operation);
          } catch (RuntimeException alertFailure) {
            /* Preserve uncertainty. */
          }
        }
      }
      Result result =
          uncertain
              ? Result.UNCERTAIN
              : failure instanceof VaultIntegrityException || failure instanceof IntegrityFailure
                  ? Result.INTEGRITY_FAILURE
                  : VaultPolicy.safeFailure(failure).code().equals(TokenErrors.DENIED.code())
                      ? Result.DENIED
                      : Result.UNAVAILABLE;
      try {
        audit.publishConfirmed(event(caller, action, state, row, id, result));
      } catch (RuntimeException auditFailure) {
        if (!uncertain) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      }
      if (result == Result.INTEGRITY_FAILURE) throw new IntegrityFailure();
      throw uncertain
          ? TokenErrors.failure(TokenErrors.UNCERTAIN)
          : VaultPolicy.safeFailure(failure);
    }
  }

  private VaultMaintenanceEvent event(
      TokenCaller caller,
      Action action,
      ControlRef state,
      @Nullable VaultRecord row,
      UUID id,
      Result result) {
    return VaultMaintenanceEvent.builder()
        .action(action)
        .caller(caller)
        .keysetId(state.keysetId())
        .keyVersion(state.version())
        .scope(row == null ? null : row.scope())
        .tokenReference(row == null ? "none" : row.token().masked())
        .result(result)
        .operationId(id)
        .at(clock.instant())
        .build();
  }
}
