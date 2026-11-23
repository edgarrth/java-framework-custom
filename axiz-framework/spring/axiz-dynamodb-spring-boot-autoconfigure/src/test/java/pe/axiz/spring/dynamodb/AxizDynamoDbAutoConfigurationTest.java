package pe.axiz.spring.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.kit.dynamodb.DynamoRepository;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Un cliente observado por aplicación, el cliente mejorado y repositorios con prefijo. */
class AxizDynamoDbAutoConfigurationTest {

  @DynamoDbBean
  public static class Item {
    private String id = "";

    @DynamoDbPartitionKey
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  /** Los records pasan por el esquema inmutable: deben estar anotados para él. */
  public record NotAnnotated(String id) {}

  /** Elemento inmutable: un record con su builder. */
  @DynamoDbImmutable(builder = Movement.Builder.class)
  public record Movement(@DynamoDbPartitionKey String id) {

    public static Builder builder() {
      return new Builder();
    }

    /** Builder usado por el cliente mejorado. */
    public static final class Builder {
      private String id = "";

      public Builder id(String value) {
        this.id = value;
        return this;
      }

      public Movement build() {
        return new Movement(id);
      }
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withConfiguration(AutoConfigurations.of(AxizDynamoDbAutoConfiguration.class))
          .withPropertyValues("axiz.aws.region=us-east-1");

  @Test
  void theClientsFollowTheAwsSettings() {
    runner
        .withPropertyValues(
            "axiz.aws.endpoint=https://localhost:4566", "axiz.dynamodb.table-prefix=payments-dev-")
        .run(
            ctx -> {
              DynamoDbClient client = ctx.getBean(DynamoDbClient.class);
              assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
              assertEquals(
                  URI.create("https://localhost:4566"),
                  client.serviceClientConfiguration().endpointOverride().orElseThrow());
              assertThat(ctx).hasSingleBean(DynamoDbEnhancedClient.class);
              DynamoRepositories repositories = ctx.getBean(DynamoRepositories.class);
              assertEquals("payments-dev-beneficiaries", repositories.tableName("beneficiaries"));
              DynamoRepository<Item> repository = repositories.create("items", Item.class);
              assertThat(repository).isNotNull();
              assertThat(repositories.create("movements", Movement.class)).isNotNull();
              assertThrows(
                  IllegalArgumentException.class,
                  () -> repositories.create("records", NotAnnotated.class),
                  "a record needs @DynamoDbImmutable");
            });
  }

  @Test
  void aDynamoDbSpecificEndpointWinsOverTheGeneralOne() {
    runner
        .withPropertyValues(
            "axiz.aws.endpoint=https://localhost:4566",
            "axiz.aws.dynamodb.endpoint=https://localhost:8000")
        .run(
            ctx ->
                assertEquals(
                    URI.create("https://localhost:8000"),
                    ctx.getBean(DynamoDbClient.class)
                        .serviceClientConfiguration()
                        .endpointOverride()
                        .orElseThrow()));
  }

  @Test
  void theServiceCanProvideItsOwnClient() {
    DynamoDbClient own = DynamoDbClient.builder().region(Region.EU_WEST_1).build();
    try {
      runner
          .withBean(DynamoDbClient.class, () -> own)
          .run(
              ctx -> {
                assertSame(own, ctx.getBean(DynamoDbClient.class));
                assertEquals("", ctx.getBean(DynamoRepositories.class).tableName(""));
              });
    } finally {
      own.close();
    }
  }
}
