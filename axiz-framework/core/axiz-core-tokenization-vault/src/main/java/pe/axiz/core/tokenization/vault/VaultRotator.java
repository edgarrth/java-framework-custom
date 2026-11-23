package pe.axiz.core.tokenization.vault;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.EnvelopeEncryptor;
import pe.axiz.core.crypto.KeyedHash;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.vault.VaultMaintenanceEvent.Action;

/**
 * Job síncrono privilegiado, separado de los endpoints de consumidores y de sus hilos de ejecución.
 * El progreso es acotado y reanudable. Un acuse perdido se reconcilia, nunca se reintenta aquí. El
 * retiro de HMAC elimina una referencia de lectura; no puede desactivar claves externas de KMS ni
 * borrar secretos.
 */
public final class VaultRotator {
  private final TokenVaultMaintenance vault;
  private final BlindIndex indexes;
  private final EnvelopeEncryptor source;
  private final VaultMaintenanceExecution execution;
  private final int maxProofPages;
  private final long proofNanos;

  private VaultRotator(Builder builder) {
    TokenVaultMaintenance vault = Objects.requireNonNull(builder.vault, "vault");
    BlindIndex indexes = Objects.requireNonNull(builder.indexes, "indexes");
    EnvelopeEncryptor source = Objects.requireNonNull(builder.source, "source");
    VaultMaintenanceAccess access = Objects.requireNonNull(builder.access, "access");
    VaultMaintenanceAudit audit = Objects.requireNonNull(builder.audit, "audit");
    VaultMaintenanceReconciliation reconciliation =
        Objects.requireNonNull(builder.reconciliation, "reconciliation");
    VaultMaintenanceAlarm alarm = Objects.requireNonNull(builder.alarm, "alarm");
    Clock clock = Objects.requireNonNull(builder.clock, "clock");
    Supplier<UUID> ids = Objects.requireNonNull(builder.ids, "ids");
    String authority = Objects.requireNonNull(builder.authority, "authority");
    int maxProofPages = builder.maxProofPages;
    Duration proofTimeout = Objects.requireNonNull(builder.proofTimeout, "proofTimeout");
    this.vault = vault;
    this.indexes = indexes;
    this.source = source;
    execution =
        new VaultMaintenanceExecution(access, audit, reconciliation, alarm, clock, ids, authority);
    if (maxProofPages < 1
        || maxProofPages > 1_000_000
        || proofTimeout.compareTo(Duration.ofSeconds(1)) < 0
        || proofTimeout.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("Invalid maintenance proof budget");
    this.maxProofPages = maxProofPages;
    proofNanos = proofTimeout.toNanos();
  }

  /**
   * Inicializa el control de envoltura independiente solo tras una prueba exitosa de generación de
   * claves.
   */
  public boolean initializeWrapping(
      TokenCaller caller, String keyset, KeyringDataKeyProvider keys) {
    var state = new WrappingKeyState(keyset, 1, keys.writeKeyId(), List.of(keys.writeKeyId()));
    return execution.run(
        caller,
        Action.INITIALIZE_WRAPPING,
        state,
        null,
        () -> {
          probe(keys);
          return vault.initializeWrapping(state);
        });
  }

  /**
   * Primero se preparan todos los lectores; la activación aplica fencing incluso a un escritor que
   * aún tiene en caché una DEK antigua.
   */
  public boolean activateWrapping(
      TokenCaller caller, WrappingKeyState expected, KeyringDataKeyProvider nextKeys) {
    return execution.run(
        caller,
        Action.ACTIVATE_WRAPPING,
        expected,
        null,
        () -> {
          String nextKey = nextKeys.writeKeyId();
          if (expected.readKeyIds().contains(nextKey))
            throw TokenErrors.failure(TokenErrors.INVALID);
          if (!nextKeys.readKeyIds().containsAll(expected.readKeyIds()))
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          var reads = new ArrayList<String>();
          reads.add(nextKey);
          reads.addAll(expected.readKeyIds());
          var next =
              new WrappingKeyState(
                  expected.keysetId(), Math.addExact(expected.version(), 1), nextKey, reads);
          probe(nextKeys);
          return vault.transitionWrapping(new WrappingKeyTransition(expected, next));
        });
  }

  /**
   * Elimina una ruta de lectura solo de esta bóveda, tras la verificación autenticada de cada fila.
   */
  public boolean retireWrapping(TokenCaller caller, WrappingKeyState expected, String obsoleteKey) {
    return execution.run(
        caller,
        Action.RETIRE_WRAPPING,
        expected,
        null,
        () -> {
          if (obsoleteKey.equals(expected.writeKeyId())
              || !expected.readKeyIds().contains(obsoleteKey))
            throw TokenErrors.failure(TokenErrors.INVALID);
          if (!vault.wrappingState(expected.keysetId()).equals(expected))
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          FingerprintKeyState fingerprints = vault.keyState(expected.keysetId());
          requireMaterial(fingerprints);
          long start = System.nanoTime();
          String cursor = null;
          int pages = 0;
          do {
            if (++pages > maxProofPages
                || Thread.currentThread().isInterrupted()
                || System.nanoTime() - start >= proofNanos)
              throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
            var page = vault.scan(expected.keysetId(), cursor, 100);
            for (VaultRecord row : page.records()) {
              execution.run(
                  caller,
                  Action.RETIRE_WRAPPING,
                  expected,
                  row,
                  () -> {
                    checked(row, fingerprints);
                    String route = KeyringDataKeyProvider.envelopeKeyId(row.ciphertext());
                    if (!expected.readKeyIds().contains(route)) throw new VaultIntegrityException();
                    if (route.equals(obsoleteKey))
                      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
                    byte[] plaintext =
                        source.decrypt(
                            row.ciphertext(),
                            VaultPolicy.ENCRYPTION_CONTEXT,
                            row.scope().binding(row.token()));
                    try (SensitiveValue value = VaultText.decode(plaintext)) {
                      value.use(
                          chars -> {
                            verifyFingerprints(row, chars);
                            return true;
                          });
                    } finally {
                      Arrays.fill(plaintext, (byte) 0);
                    }
                    return true;
                  });
            }
            String nextCursor = page.nextCursor();
            if (nextCursor != null && nextCursor.equals(cursor))
              throw new VaultIntegrityException();
            cursor = nextCursor;
          } while (cursor != null);
          if (System.nanoTime() - start >= proofNanos || Thread.currentThread().isInterrupted())
            throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
          var next =
              new WrappingKeyState(
                  expected.keysetId(),
                  Math.addExact(expected.version(), 1),
                  expected.writeKeyId(),
                  expected.readKeyIds().stream().filter(key -> !key.equals(obsoleteKey)).toList());
          return vault.transitionWrapping(new WrappingKeyTransition(expected, next));
        });
  }

  private void probe(KeyringDataKeyProvider keys) {
    try (var generated = keys.generate(VaultPolicy.ENCRYPTION_CONTEXT)) {
      if (generated.encrypted().length == 0) throw new VaultIntegrityException();
    }
  }

  private void verifyFingerprints(VaultRecord row, char[] canonical) {
    for (KeyedHash stored : row.fingerprints())
      if (!stored.equals(indexes.index(canonical, row.scope().indexScope(), stored.keyId())))
        throw new VaultIntegrityException();
  }

  public boolean initialize(TokenCaller caller, String keyset, String firstKey) {
    var initial = new FingerprintKeyState(keyset, 1, firstKey, List.of(firstKey));
    return execution.run(
        caller,
        Action.INITIALIZE,
        initial,
        null,
        () -> {
          requireMaterial(initial);
          return vault.initialize(initial);
        });
  }

  public boolean activate(TokenCaller caller, FingerprintKeyState expected, String nextKey) {
    return execution.run(
        caller,
        Action.ACTIVATE,
        expected,
        null,
        () -> {
          VaultIdentifiers.key(nextKey);
          if (expected.readKeyIds().contains(nextKey))
            throw TokenErrors.failure(TokenErrors.INVALID);
          var reads = new ArrayList<String>();
          reads.add(nextKey);
          reads.addAll(expected.readKeyIds());
          var next =
              new FingerprintKeyState(
                  expected.keysetId(), Math.addExact(expected.version(), 1), nextKey, reads);
          requireMaterial(next);
          return vault.transition(new VaultKeyTransition(expected, next));
        });
  }

  /** Agrega la huella actual y preserva todas las referencias antiguas hasta la fase de poda. */
  public VaultMaintenanceBatch reindex(
      TokenCaller caller, FingerprintKeyState state, @Nullable String cursor, int limit) {
    return batch(caller, Action.REINDEX, state, cursor, limit, null, null);
  }

  /**
   * Usa un cifrador destino configurado por separado, preservando token, alcance, vida e índices.
   * El descifrado de origen y de destino debe mantenerse disponible durante todo un despliegue con
   * textos cifrados mixtos. Este método no autoriza el retiro de una clave de KMS.
   */
  public VaultMaintenanceBatch reencrypt(
      TokenCaller caller,
      FingerprintKeyState state,
      @Nullable String cursor,
      int limit,
      EnvelopeEncryptor target) {
    return batch(
        caller, Action.REENCRYPT, state, cursor, limit, null, Objects.requireNonNull(target));
  }

  public VaultPrunePlan preparePrune(
      TokenCaller caller, FingerprintKeyState state, String obsoleteKey) {
    return execution.run(
        caller,
        Action.PRUNE,
        state,
        null,
        () -> {
          obsolete(state, obsoleteKey);
          prove(
              caller,
              Action.PRUNE,
              state,
              row ->
                  row.fingerprints().stream()
                      .anyMatch(hash -> hash.keyId().equals(state.writeKeyId())));
          return new VaultPrunePlan(state, obsoleteKey);
        });
  }

  public VaultMaintenanceBatch prune(
      TokenCaller caller, VaultPrunePlan plan, @Nullable String cursor, int limit) {
    return batch(caller, Action.PRUNE, plan.state(), cursor, limit, plan.obsoleteKey(), null);
  }

  public boolean retire(TokenCaller caller, FingerprintKeyState state, String obsoleteKey) {
    return execution.run(
        caller,
        Action.RETIRE,
        state,
        null,
        () -> {
          obsolete(state, obsoleteKey);
          prove(
              caller,
              Action.RETIRE,
              state,
              row ->
                  row.fingerprints().stream().noneMatch(hash -> hash.keyId().equals(obsoleteKey)));
          proveNoReferences(state, obsoleteKey);
          var next =
              new FingerprintKeyState(
                  state.keysetId(),
                  Math.addExact(state.version(), 1),
                  state.writeKeyId(),
                  state.readKeyIds().stream().filter(key -> !key.equals(obsoleteKey)).toList());
          return vault.transition(new VaultKeyTransition(state, next));
        });
  }

  private VaultMaintenanceBatch batch(
      TokenCaller caller,
      Action action,
      FingerprintKeyState state,
      @Nullable String cursor,
      int limit,
      @Nullable String obsoleteKey,
      @Nullable EnvelopeEncryptor target) {
    return execution.run(
        caller,
        action,
        state,
        null,
        () -> {
          current(state);
          var page = vault.scan(state.keysetId(), cursor, limit);
          int migrated = 0;
          int changed = 0;
          for (VaultRecord row : page.records()) {
            if (Thread.currentThread().isInterrupted())
              throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
            MigrationOutcome outcome =
                execution.run(
                    caller, action, state, row, () -> migrate(row, state, obsoleteKey, target));
            if (outcome == MigrationOutcome.MIGRATED) migrated++;
            if (outcome == MigrationOutcome.CHANGED) changed++;
          }
          return new VaultMaintenanceBatch(
              page.records().size(), migrated, changed, page.nextCursor());
        });
  }

  private MigrationOutcome migrate(
      VaultRecord row,
      FingerprintKeyState state,
      @Nullable String obsoleteKey,
      @Nullable EnvelopeEncryptor target) {
    checked(row, state);
    List<KeyedHash> hashes;
    byte[] ciphertext = row.ciphertext();
    if (obsoleteKey != null) {
      if (row.fingerprints().stream().noneMatch(hash -> hash.keyId().equals(state.writeKeyId())))
        throw new VaultIntegrityException();
      hashes =
          row.fingerprints().stream().filter(hash -> !hash.keyId().equals(obsoleteKey)).toList();
      if (hashes.size() == row.fingerprints().size()) return MigrationOutcome.UNCHANGED;
    } else {
      byte[] plaintext =
          source.decrypt(
              ciphertext, VaultPolicy.ENCRYPTION_CONTEXT, row.scope().binding(row.token()));
      try (SensitiveValue value = VaultText.decode(plaintext)) {
        var write =
            value.use(
                chars -> {
                  verifyFingerprints(row, chars);
                  return indexes.index(chars, row.scope().indexScope(), state.writeKeyId());
                });
        if (target == null && row.fingerprints().contains(write)) return MigrationOutcome.UNCHANGED;
        hashes = new ArrayList<>(row.fingerprints());
        if (target == null) hashes.add(write);
        else
          ciphertext =
              target.encrypt(
                  plaintext, VaultPolicy.ENCRYPTION_CONTEXT, row.scope().binding(row.token()));
      } finally {
        Arrays.fill(plaintext, (byte) 0);
      }
    }
    var replacement =
        VaultRecord.builder()
            .token(row.token())
            .scope(row.scope())
            .keysetId(row.keysetId())
            .fingerprints(hashes)
            .revision(Math.addExact(row.revision(), 1))
            .ciphertext(ciphertext)
            .createdAt(row.createdAt())
            .expiresAt(row.expiresAt())
            .metadata(row.metadata())
            .build();
    MigrationOutcome outcome = vault.replace(row, replacement, state);
    if (outcome == MigrationOutcome.INTEGRITY_FAILURE) throw new VaultIntegrityException();
    if (outcome == MigrationOutcome.STALE_KEY_STATE)
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
    return outcome;
  }

  private void prove(
      TokenCaller caller,
      Action action,
      FingerprintKeyState state,
      Predicate<VaultRecord> condition) {
    current(state);
    long start = System.nanoTime();
    String cursor = null;
    int pages = 0;
    do {
      if (++pages > maxProofPages
          || Thread.currentThread().isInterrupted()
          || System.nanoTime() - start >= proofNanos)
        throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      var page = vault.scan(state.keysetId(), cursor, 100);
      for (VaultRecord row : page.records()) {
        execution.authorize(caller, action, state, row);
        checked(row, state);
        if (!condition.test(row)) throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      }
      String nextCursor = page.nextCursor();
      if (nextCursor != null && nextCursor.equals(cursor)) throw new VaultIntegrityException();
      cursor = nextCursor;
    } while (cursor != null);
    // No es una instantánea de la base de datos: la eliminación es monotónica y cada escritura
    // tiene fencing con la misma versión. Una prueba desfasada no puede autorizar escrituras
    // después de otra activación o retiro.
    current(state);
  }

  private void obsolete(FingerprintKeyState state, String key) {
    if (key.equals(state.writeKeyId()) || !state.readKeyIds().contains(key))
      throw TokenErrors.failure(TokenErrors.INVALID);
  }

  private void proveNoReferences(FingerprintKeyState state, String key) {
    long start = System.nanoTime();
    String cursor = null;
    int pages = 0;
    do {
      if (++pages > maxProofPages
          || Thread.currentThread().isInterrupted()
          || System.nanoTime() - start >= proofNanos)
        throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
      var page = vault.references(state.keysetId(), key, cursor, 100);
      if (page.references() != 0) throw new VaultIntegrityException();
      String nextCursor = page.nextCursor();
      if (nextCursor != null && nextCursor.equals(cursor)) throw new VaultIntegrityException();
      cursor = nextCursor;
    } while (cursor != null);
    current(state);
  }

  private void current(FingerprintKeyState state) {
    requireMaterial(state);
    if (!vault.keyState(state.keysetId()).equals(state))
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
  }

  private void requireMaterial(FingerprintKeyState state) {
    if (!indexes.keyIds().containsAll(state.readKeyIds()))
      throw TokenErrors.failure(TokenErrors.UNAVAILABLE);
  }

  private void checked(VaultRecord row, FingerprintKeyState state) {
    if (!row.keysetId().equals(state.keysetId())
        || row.fingerprints().stream().anyMatch(hash -> !state.readKeyIds().contains(hash.keyId())))
      throw new VaultIntegrityException();
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
    private @Nullable BlindIndex indexes = null;
    private @Nullable EnvelopeEncryptor source = null;
    private @Nullable VaultMaintenanceAccess access = null;
    private @Nullable VaultMaintenanceAudit audit = null;
    private @Nullable VaultMaintenanceReconciliation reconciliation = null;
    private @Nullable VaultMaintenanceAlarm alarm = null;
    private @Nullable Clock clock = null;
    private @Nullable Supplier<UUID> ids = null;
    private @Nullable String authority = null;
    private int maxProofPages = 0;
    private @Nullable Duration proofTimeout = null;

    private Builder() {}

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The vault is a borrowed persistence port; identity, transaction state and lifecycle must be shared.")
    public Builder vault(TokenVaultMaintenance value) {
      this.vault = value;
      return this;
    }

    public Builder indexes(BlindIndex value) {
      this.indexes = value;
      return this;
    }

    public Builder source(EnvelopeEncryptor value) {
      this.source = value;
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

    public Builder maxProofPages(int value) {
      this.maxProofPages = value;
      return this;
    }

    public Builder proofTimeout(Duration value) {
      this.proofTimeout = value;
      return this;
    }

    public VaultRotator build() {
      return new VaultRotator(this);
    }
  }
}
