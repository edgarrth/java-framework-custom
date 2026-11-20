# Protected data: executable consumer examples

Run from `axiz-demo` with JDK 25, installed Axiz artifacts and Docker:

```sh
./mvnw -pl examples/protected-data -Pquality verify
```

This test-only module provisions disposable LocalStack DynamoDB, KMS, SQS and Secrets
Manager resources, plus authenticated Valkey. It does not contact AWS remotely and is
not a deployable vault service. The normal demo reactor runs it; Docker absence fails.

`ProtectedDataExampleTest` composes the real Spring provider integrations and demonstrates:

- PAN and DNI tokenization into a central encrypted DynamoDB vault, repeat lookup and
  authorized reveal. Client-visible results contain a token and permitted metadata;
  ciphertext and equality fingerprints remain inside the vault.
- Independent DNI field protection: receive ciphertext plus keyed HMAC, store both in
  an application-owned DynamoDB table, search by the HMAC, and decrypt with tenant,
  domain and stable row binding. This path does not invoke tokenization.
- Separate encryption/index keys resolved from Secrets Manager, KMS envelope encryption,
  authenticated distributed budgets and acknowledged HMAC-sealed SQS audit records.
- `VaultMaintenanceRecipe` demonstrates administrative authorization, index rotation/reindex,
  pruning obsolete search references, KMS wrapping-key rotation/re-encryption, guarded key
  retirement and retention expiry. Tokens remain stable across rotation.

Rotation is an operational protocol. Provision the new keys and deploy readers that know both
versions before activating a new write version. Quiesce old writers, activate the version, replace
the immutable runtime configuration with new writers and resume traffic. The vault rejects stale
writers. Run bounded migration pages until no old records remain; prove that state before retiring
the old version. Index pruning uses the maintenance preparation/proof contract. External KMS key
disablement must also account for other keysets, backups and retention obligations.

Administrative calls use a separate principal with only the maintenance scope and trusted tenant,
keyset and clock. Retention is demonstrated with a controlled test clock, never a date supplied
by a public caller. An uncertain maintenance result is sent to a dedicated reconciliation queue
with action, operation ID, keyset/version and scope. That restricted queue may need the full token
to perform reconciliation; application logs retain only redacted diagnostics. Operators must
consume and reconcile it before treating an uncertain outcome as complete.

For remote application consumers, see
[`RemoteTokenClientRecipeTest`](../provider-recipes/src/test/java/pe/axiz/demo/providers/RemoteTokenClientRecipeTest.java).
It shows the public token client on both web stacks. `ReactiveVaultRecipe` uses native asynchronous DynamoDB, KMS, Redis and acknowledged SQS audit
providers, checks tenant isolation and closes sensitive results inside completion callbacks. This module
concentrates on the real vault providers and independent encryption/index operations.

Identities are deliberately supplied at the trusted **internal** `VaultOperations`
boundary. These examples do not implement JWT authentication or expose an HTTP API.
The framework's `VaultAcceptanceIT` separately exercises signed JWT and HTTP boundaries.
A deployed vault must verify identity before constructing `TokenCaller`, configure TLS,
restricted IAM, key custody/rotation, retention and reconciliation operations. LocalStack
functional acceptance is not a PCI DSS certification or proof of AWS IAM enforcement.

The test fixture creates tables and initial key-state records as an infrastructure owner.
The runtime provider verifies them and does not create tables. Fixture cleanup closes
clients, keys and containers; test output never prints values or secret material.
