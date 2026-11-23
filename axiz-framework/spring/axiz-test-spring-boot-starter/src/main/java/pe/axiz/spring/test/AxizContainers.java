package pe.axiz.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.quality.containers.TestContainerImages;

/**
 * Contenedores que coinciden con la infraestructura aprobada: Aurora PostgreSQL, MSK (Kafka),
 * ElastiCache (Valkey) y LocalStack para DynamoDB, SQS, SNS, S3, KMS, Secrets Manager y Parameter
 * Store. Las imágenes se construyen de forma diferida a partir de las recetas centrales parcheadas
 * y deben superar el control de vulnerabilidades.
 */
public final class AxizContainers {

  private AxizContainers() {}

  public static PostgreSQLContainer postgres() {
    return TestContainerImages.postgres()
        .withDatabaseName("app")
        .withUsername("app")
        .withPassword("app");
  }

  public static KafkaContainer kafka() {
    return TestContainerImages.kafka();
  }

  @SuppressWarnings("resource")
  public static GenericContainer<?> valkey() {
    return TestContainerImages.valkey();
  }

  public static LocalStackContainer localstack() {
    return TestContainerImages.localstack()
        .withServices("dynamodb", "sqs", "sns", "s3", "kms", "secretsmanager", "ssm");
  }
}
