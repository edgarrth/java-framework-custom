package pe.axiz.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import pe.axiz.quality.containers.TestContainerImages;

/**
 * Containers matching the approved infrastructure: Aurora PostgreSQL, MSK (Kafka), ElastiCache
 * (Valkey) and LocalStack for DynamoDB, SQS, SNS, S3, KMS, Secrets Manager and Parameter Store.
 * Images are built lazily from the central patched recipes and must pass the vulnerability gate.
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
