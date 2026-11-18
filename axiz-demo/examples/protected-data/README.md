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

Identities are deliberately supplied at the trusted **internal** `VaultOperations`
boundary. These examples do not implement JWT authentication or expose an HTTP API.
The framework's `VaultAcceptanceIT` separately exercises signed JWT and HTTP boundaries.
A deployed vault must verify identity before constructing `TokenCaller`, configure TLS,
restricted IAM, key custody/rotation, retention and reconciliation operations. LocalStack
functional acceptance is not a PCI DSS certification or proof of AWS IAM enforcement.

The test fixture creates tables and initial key-state records as an infrastructure owner.
The runtime provider verifies them and does not create tables. Fixture cleanup closes
clients, keys and containers; test output never prints values or secret material.
