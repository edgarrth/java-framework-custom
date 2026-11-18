package pe.axiz.spring.aws;

/** Framework capability level for credential selection. */
public enum AwsCapability {
  UNSPECIFIED,
  AUDIT,
  STORAGE,
  MESSAGING,
  CRYPTO,
  SECRETS,
  /** Generic DynamoDB client and repositories; separate from stores backed by that service. */
  DYNAMODB,
  /** Runtime token records and indexes; never the migration or key-administration identity. */
  TOKENIZATION_VAULT,
  /** Independently authorized privileged token maintenance. */
  TOKENIZATION_MAINTENANCE,
  IDEMPOTENCY,
  INBOX,
  LOCK,
  SEQUENCE,
  SCHEDULING,
  JPA,
  R2DBC
}
