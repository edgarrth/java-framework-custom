package pe.axiz.core.tokenization.vault;

import org.jspecify.annotations.Nullable;

/**
 * Privileged, synchronous maintenance job port. Never exposed as a public tokenization endpoint or
 * implemented by wrapping this blocking port in a reactive executor. The operational server uses a
 * different identity that cannot write control items or scan this table.
 */
public interface TokenVaultMaintenance extends TokenVault {
  /** Independent, strongly consistent wrapping-key control; no fallback to fingerprint keys. */
  WrappingKeyState wrappingState(String keysetId);

  /** Initializes a wrapping control once, with version 1 and its single write route. */
  boolean initializeWrapping(WrappingKeyState initial);

  /** Conditional transition after the governed wrapping-key rotation protocol. */
  boolean transitionWrapping(WrappingKeyTransition transition);

  /** Initial state has version 1 and only its write key. Never overwrites an existing control. */
  boolean initialize(FingerprintKeyState initial);

  /** Consistent, bounded scan of records for one keyset; empty pages can still have a cursor. */
  VaultScanPage scan(String keysetId, @Nullable String cursor, int limit);

  /** Counts old reference items, including orphans, using the same bounded traversal semantics. */
  VaultReferencePage references(String keysetId, String keyId, @Nullable String cursor, int limit);

  /** CAS on the entire expected control state; issued only by the governed rotation protocol. */
  boolean transition(VaultKeyTransition transition);

  /**
   * Atomically replaces an unchanged record and its owned references under the control barrier. May
   * add only the current write fingerprint; cannot resurrect a retired read fingerprint. Removing
   * an old fingerprint requires retaining the current write fingerprint.
   */
  MigrationOutcome replace(
      VaultRecord expected, VaultRecord replacement, FingerprintKeyState state);
}
