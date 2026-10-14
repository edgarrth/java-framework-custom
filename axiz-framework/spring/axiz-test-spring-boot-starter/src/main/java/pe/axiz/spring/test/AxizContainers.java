package pe.axiz.spring.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers matching the approved infrastructure: Aurora PostgreSQL, MSK (Kafka), ElastiCache
 * (Valkey) and LocalStack for DynamoDB, SQS, SNS, S3, KMS, Secrets Manager and Parameter Store.
 * Images are pinned; Renovate keeps them current.
 */
public final class AxizContainers {

  public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17.6-alpine");
  public static final DockerImageName KAFKA = DockerImageName.parse("apache/kafka-native:4.1.0");
  public static final DockerImageName VALKEY = DockerImageName.parse("valkey/valkey:8.1-alpine");
  public static final DockerImageName LOCALSTACK =
      DockerImageName.parse("localstack/localstack:4.9");

  private AxizContainers() {}

  public static PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(POSTGRES)
        .withDatabaseName("app")
        .withUsername("app")
        .withPassword("app");
  }

  public static KafkaContainer kafka() {
    return new KafkaContainer(KAFKA);
  }

  @SuppressWarnings("resource")
  public static GenericContainer<?> valkey() {
    return new GenericContainer<>(VALKEY).withExposedPorts(6379);
  }

  public static LocalStackContainer localstack() {
    return new LocalStackContainer(LOCALSTACK)
        .withServices("dynamodb", "sqs", "sns", "s3", "kms", "secretsmanager", "ssm");
  }
}
