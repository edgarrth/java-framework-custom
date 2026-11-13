package pe.axiz.spring.foundation.aws;

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
  IDEMPOTENCY,
  INBOX,
  LOCK,
  SEQUENCE,
  SCHEDULING,
  JPA,
  R2DBC
}
