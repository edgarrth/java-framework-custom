package pe.axiz.spring.foundation.aws;

/** Framework capability level for credential selection. */
public enum AwsCapability {
  UNSPECIFIED,
  AUDIT,
  STORAGE,
  MESSAGING,
  CRYPTO,
  SECRETS,
  DYNAMODB,
  IDEMPOTENCY,
  INBOX,
  LOCK,
  SEQUENCE,
  SCHEDULING,
  JPA,
  R2DBC
}
