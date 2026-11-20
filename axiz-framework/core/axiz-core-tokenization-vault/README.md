# axiz-core-tokenization-vault

Explicit-principal token vault orchestration and atomic persistence ports.

This module owns the `pe.axiz.core.tokenization.vault` API.

`VaultTokenizer` and `AsyncVaultTokenizer` accept a verified `TokenCaller`, a registered type,
and an authorized domain. They share the same access, profile, canonicalization, expiry and
fingerprint policy. The asynchronous engine uses native completion stages. Cancelling a result
does not cancel an accepted mutation: it waits for its finite provider deadline, records the
outcome and releases its resources. Every integration must enforce those provider deadlines.

Public results contain an opaque token and explicitly allowed metadata. Ciphertext and HMAC
remain inside the vault. The independent `FieldProtector` API belongs to `axiz-core-crypto`.
Neither class configures an in-memory fallback, anonymous identity or permissive audit sink.

## Privileged maintenance

`VaultRotator` is a synchronous administrative job. It is separate from consumer endpoints
and must use an independently authorized storage identity. Its mandatory policy checks the
administrative authority, keyset and every record's scope. It confirms a STARTED audit event
before decrypting or writing and confirms the outcome before reporting success. No source
value, ciphertext, HMAC or full token is included in those audit events.

HMAC rotation follows these steps:

1. Install distinct new key material on all participating replicas. Keep the old read material.
2. `activate(caller, expectedState, newKey)` atomically increments the control version, selects
   the new write key and preserves all existing read keys. A missing key fails closed.
3. Traverse `reindex` from a null cursor until `nextCursor` is null. Empty pages can still have
   a cursor. The job authenticates ciphertext and stored HMACs, adds the current fingerprint,
   and retains old references. A changed row is reported separately and requires revisiting.
4. `preparePrune` traverses the complete keyset and requires the current fingerprint on every
   remaining row. Its plan is tied to that exact control version.
5. Traverse `prune` with that plan. Conditional transactions remove only owned references and
   retain the current fingerprint. Delayed jobs cannot reintroduce an old fingerprint.
6. `retire` proves both that no record depends on the old key and that no old reference item
   remains, including orphan references. It then changes the control state conditionally.
   Only after every keyset/deployment dependency is clear may the operator retire secret material.

Proof traversals have explicit page and time budgets; exhaustion or interruption fails closed.
A scan is not a global snapshot. Safety relies on stable record keys, monotonic removal and
the version barrier on **every** creation and migration. Restore/import tools and direct table
writers must preserve these invariants. A restore requires reconciliation before serving traffic.
Do not reuse retired key identifiers for different material.

`reencrypt` accepts a separate target `EnvelopeEncryptor`, authenticates the source, and preserves
the token, indexes, metadata and lifetime while incrementing the revision. During a mixed rollout,
the operator must keep decryption available for both source and target envelopes. The core
`KeyringDataKeyProvider` and `KeyringAsyncDataKeyProvider` support explicit routing to at most
16 wrapping-key providers; Spring KMS composes them from an optional immutable-ARN key ring.

Wrapping-key rotation uses a separate `WrappingKeyState`, independent of the HMAC version:

1. `initializeWrapping` establishes the initial route after a key-generation probe. Configure
   routed providers before the vault's first write; both controls are required by DynamoDB.
2. Prepare all readers, then call `activateWrapping` with a ring that retains every active reader
   and selects the new writer. Initialization/activation probes the new provider and commits a
   conditional durable control change. Existing DEK caches do not bypass the write barrier.
3. Traverse `reencrypt` with a source supporting old envelopes and a target writing the current
   route. The same token, scope, lifetime and fingerprints survive the migration.
4. `retireWrapping` rejects any dependent record. It authenticates every remaining envelope and
   checks its HMACs under administrative authorization and confirmed audit, then conditionally
   removes the old read route. Delayed creations and recipher jobs cannot reintroduce that route.

The DynamoDB adapter permanently reserves identifiers, preventing a retired identifier from
being reactivated under a later generation. A failed compare-and-set reports conflict; a lost
acknowledgement still requires reconciliation. No job disables or deletes a KMS key. Removing
its vault read route is only one prerequisite for retiring external material: backups, replicas
and other applications must also be accounted for by the operator.

A lost storage acknowledgement produces `AXZ-TOKEN-0504` and restricted durable reconciliation.
There is no automatic retry of that uncertain mutation. If reconciliation fails, a technical
alarm is raised and the outcome remains uncertain. A failed audit after a confirmed write does
not compensate by deleting or rolling back the durable association. It returns the same
`AXZ-TOKEN-0504` uncertainty outcome and records `COMMITTED_AUDIT_UNCONFIRMED`, including the
operation ID and restricted token reference for audit repair; it does not encourage repeating the
write. Maintenance reconciliation distinguishes a missing storage acknowledgement (`UNCERTAIN`)
from a confirmed `SUCCESS`/`CONFLICT` whose terminal audit was not acknowledged. This applies to
both imperative and native asynchronous tokenization. A reconciliation failure raises the
mandatory alarm while preserving the uncertain response.

## Association integrity and resource cleanup

Lookup and deduplicating tokenization decrypt the candidate inside the vault, verify every stored
fingerprint against the canonical plaintext and scope with its registered key, and recompute the
profile metadata. Detokenization performs the same authentication before exposing plaintext.
A candidate returned after a uniqueness race is checked too. Copied references/fingerprints or
metadata cannot make a different value look like the requested value; failures deny disclosure
and audit integrity failure. Temporary plaintext/encoded comparison buffers are erased.

The envelope authenticates the value and its existing token/scope AAD. This does not provide
cryptographic anti-rollback of an entire database snapshot or authenticate every storage header:
creation/expiry/revision consistency also relies on trusted storage IAM, conditional writes,
retention controls and reconciliation of restores. Never claim protection against arbitrary
privileged storage rollback from AEAD or the fingerprint check alone.

Once the business result and required audit are confirmed, failure to release a Redis permit does
not replace that result or emit a second contradictory business outcome. The reservation remains
conservative and the provider raises its administrative reconciliation alarm. Confirming business
success does not claim that capacity was released. Sync and native async engines share this rule.

Internal PAN detection and blind-index authentication now borrow mutable character arrays and
wipe temporary buffers instead of calling `SensitiveValue.reveal()`. `BlindIndex` also accepts
an owned `char[]` without changing the persisted index format. Erasure remains best effort in the
JVM: request parsing, a custom canonicalization profile, or an explicit `reveal()` can create
immutable plaintext strings. Configure the JVM/host to protect heap/core dumps and avoid retaining
request bodies; this API does not certify end-to-end absence of plaintext from memory.
