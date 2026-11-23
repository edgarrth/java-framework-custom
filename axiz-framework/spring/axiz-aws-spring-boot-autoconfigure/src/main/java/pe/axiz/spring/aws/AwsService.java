package pe.axiz.spring.aws;

/**
 * Nivel de servicio AWS para la selección de credenciales, independiente de las capacidades del
 * framework.
 */
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
