package pe.axiz.spring.aws;

/** Nivel de capacidad del framework para la selección de credenciales. */
public enum AwsCapability {
  UNSPECIFIED,
  AUDIT,
  STORAGE,
  MESSAGING,
  CRYPTO,
  SECRETS,
  /**
   * Cliente genérico de DynamoDB y repositorios; separado de los almacenes respaldados por ese
   * servicio.
   */
  DYNAMODB,
  /**
   * Registros e índices de tokens en tiempo de ejecución; nunca la identidad de migración ni de
   * administración de claves.
   */
  TOKENIZATION_VAULT,
  /** Mantenimiento privilegiado de tokens autorizado de forma independiente. */
  TOKENIZATION_MAINTENANCE,
  IDEMPOTENCY,
  INBOX,
  LOCK,
  SEQUENCE,
  SCHEDULING,
  JPA,
  R2DBC
}
