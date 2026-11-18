package pe.axiz.spring.aws;

/** AWS service level for credential selection, independent of framework capabilities. */
public enum AwsService {
  UNSPECIFIED,
  S3,
  SQS,
  SNS,
  MSK,
  DYNAMODB,
  KMS,
  SECRETS_MANAGER,
  SSM,
  RDS
}
