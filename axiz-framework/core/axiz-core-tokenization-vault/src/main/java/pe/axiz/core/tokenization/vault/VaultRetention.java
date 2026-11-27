package pe.axiz.core.tokenization.vault;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Action;

/**
 * Job de retención privilegiado y explícito. Borra las asociaciones expiradas y sus referencias
 * propias en una única operación condicional del almacén, sin descifrado ni TTL asíncrono. Se
 * ejecuta en un worker de mantenimiento dedicado; la identidad HTTP operativa no debe recibir IAM
 * de recorrido ni de escritura de control.
 */
public final class VaultRetention {
  private final TokenVaultMaintenance vault;
  private final Clock clock;
  private final VaultMaintenanceExecution execution;
  private final long budgetNanos;

  private VaultRetention(Builder builder) {
    TokenVaultMaintenance vault = Objects.requireNonNull(builder.vault, "vault");
    VaultMaintenanceAccess access = Objects.requireNonNull(builder.access, "access");
    VaultMaintenanceAudit audit = Objects.requireNonNull(builder.audit, "audit");
    VaultMaintenanceReconciliation reconciliation =
        Objects.requireNonNull(builder.reconciliation, "reconciliation");
    VaultMaintenanceAlarm alarm = Objects.requireNonNull(builder.alarm, "alarm");
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    Supplier<UUID> ids = Objects.requireNonNull(builder.ids, "ids");
    String authority = Objects.requireNonNull(builder.authority, "authority");
    Duration batchTimeout = Objects.requireNonNull(builder.batchTimeout, "batchTimeout");
    this.vault = vault;
    this.clock = clock;
    execution =
        new VaultMaintenanceExecution(access, audit, reconciliation, alarm, clock, ids, authority);
    if (batchTimeout.isNegative()
        || batchTimeout.isZero()
        || batchTimeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid retention batch budget");
    budgetNanos = batchTimeout.toNanos();
  }

  /**
   * Visita una página acotada. El corte proviene del reloj del servidor, nunca de una marca de
   * tiempo del consumidor. Un cursor devuelto se persiste solo tras el éxito; los recorridos
   * repetidos cubren de forma segura la rotación o el borrado concurrentes. Los acuses de escritura
   * desconocidos se reconcilian y nunca se reintentan aquí.
   */
  public VaultRetentionBatch purge(
      TokenCaller caller, FingerprintKeyState state, @Nullable String cursor, int limit) {
    return execution.run(
        caller,
        Action.PURGE,
        state,
        null,
        () -> {
          if (limit < 1 || limit > 100) throw TokenErrors.failure(TokenErrors.INVALID);
          long started = System.nanoTime();
          checkBudget(started);
          if (!vault.keyState(state.keysetId()).equals(state))
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          var cutoff = clock.instant();
          var page = vault.scan(state.keysetId(), cursor, limit);
          String nextCursor = page.nextCursor();
          if (page.records().size() > limit || (nextCursor != null && nextCursor.equals(cursor)))
            throw new VaultIntegrityException();
          int deleted = 0;
          int absent = 0;
          int changed = 0;
          int retained = 0;
          for (VaultRecord row : page.records()) {
            checkBudget(started);
            if (!row.keysetId().equals(state.keysetId())) throw new VaultIntegrityException();
            execution.authorize(caller, Action.PURGE, state, row);
            if (row.expiresAt().isAfter(cutoff)) {
              retained++;
              continue;
            }
            DeleteOutcome result =
                execution.run(
                    caller,
                    Action.PURGE,
                    state,
                    row,
                    () -> {
                      checkBudget(started);
                      DeleteOutcome outcome = vault.delete(row);
                      if (outcome == DeleteOutcome.INTEGRITY_FAILURE)
                        throw new VaultIntegrityException();
                      return outcome;
                    });
            switch (result) {
              case DELETED -> deleted++;
              case NOT_FOUND -> absent++;
              case CHANGED -> changed++;
              default -> throw new VaultIntegrityException();
            }
          }
          checkBudget(started);
          return new VaultRetentionBatch(
              page.records().size(), deleted, absent, changed, retained, nextCursor);
        });
  }

  private void checkBudget(long started) {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= budgetNanos)
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
  }

  /** Construcción nominal; build verifica los valores obligatorios y las invariantes. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Estado mutable de construcción; se usa un builder por operación, nunca como estado compartido.
   */
  public static final class Builder {
    private @Nullable TokenVaultMaintenance vault = null;
    private @Nullable VaultMaintenanceAccess access = null;
    private @Nullable VaultMaintenanceAudit audit = null;
    private @Nullable VaultMaintenanceReconciliation reconciliation = null;
    private @Nullable VaultMaintenanceAlarm alarm = null;
    private @Nullable Clock clock = null;
    private @Nullable Supplier<UUID> ids = null;
    private @Nullable String authority = null;
    private @Nullable Duration batchTimeout = null;

    private Builder() {}

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The vault is a borrowed persistence port; identity, transaction state and lifecycle must be shared.")
    public Builder vault(TokenVaultMaintenance value) {
      this.vault = value;
      return this;
    }

    public Builder access(VaultMaintenanceAccess value) {
      this.access = value;
      return this;
    }

    public Builder audit(VaultMaintenanceAudit value) {
      this.audit = value;
      return this;
    }

    public Builder reconciliation(VaultMaintenanceReconciliation value) {
      this.reconciliation = value;
      return this;
    }

    public Builder alarm(VaultMaintenanceAlarm value) {
      this.alarm = value;
      return this;
    }

    public Builder clock(Clock value) {
      this.clock = value;
      return this;
    }

    public Builder ids(Supplier<UUID> value) {
      this.ids = value;
      return this;
    }

    public Builder authority(String value) {
      this.authority = value;
      return this;
    }

    public Builder batchTimeout(Duration value) {
      this.batchTimeout = value;
      return this;
    }

    public VaultRetention build() {
      return new VaultRetention(this);
    }
  }
}
