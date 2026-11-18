package pe.axiz.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import pe.axiz.quality.containers.TestContainerImages;

/**
 * Containers matching the approved infrastructure: Aurora PostgreSQL, MSK (Kafka), ElastiCache
 * (Valkey) and LocalStack for DynamoDB, SQS, SNS, S3, KMS, Secrets Manager and Parameter Store.
 * PostgreSQL, Valkey and Kafka are built lazily from the central patched recipes. Other provider
 * images must independently pass the image vulnerability gate before release.
 */
public final class AxizContainers {

  public static final DockerImageName LOCALSTACK =
      DockerImageName.parse("localstack/localstack:4.9");

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
    return new LocalStackContainer(LOCALSTACK)
        .withServices("dynamodb", "sqs", "sns", "s3", "kms", "secretsmanager", "ssm");
  }
}
