package pe.axiz.spring.tokenization.vault.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbAsyncTokenVault;
import pe.axiz.adapter.tokenization.dynamodb.DynamoDbTokenVault;
import pe.axiz.core.crypto.AsyncDataKeyProvider;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.crypto.DataKeyProvider;
import pe.axiz.core.crypto.KeyringAsyncDataKeyProvider;
import pe.axiz.core.crypto.KeyringDataKeyProvider;
import pe.axiz.core.errors.AxizException;
import pe.axiz.core.tokenization.vault.AsyncTokenVault;
import pe.axiz.core.tokenization.vault.TokenVault;
import pe.axiz.spring.tokenization.vault.AxizTokenizationVaultProperties;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveDescription;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;

class DynamoVaultWiringTest {
  private static final AutoConfigurations CONFIG =
      AutoConfigurations.of(AxizTokenizationDynamoDbAutoConfiguration.class);
  private static final String[] VAULT_PROPERTIES = {
    "axiz.tokenization.vault.auth.fixed-tenant=test",
    "axiz.tokenization.vault.auth.client-claim=client_id",
    "axiz.tokenization.vault.auth.domains-claim=domains",
    "axiz.tokenization.vault.auth.authority-prefix=SCOPE_tokens:",
    "axiz.tokenization.vault.types.DNI.profile=dni.pe",
    "axiz.tokenization.vault.types.DNI.version=1",
    "axiz.tokenization.vault.types.DNI.keyset=identity",
    "axiz.tokenization.vault.types.DNI.domains=payments",
    "axiz.tokenization.vault.types.DNI.purposes=support",
    "axiz.tokenization.vault.types.DNI.retention=1d"
  };

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AxizTokenizationVaultProperties.class)
  static class Properties {}

  private static ApplicationContextRunner base() {
    return new ApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withUserConfiguration(Properties.class)
        .withPropertyValues(VAULT_PROPERTIES)
        .withBean(BlindIndex.class, () -> new BlindIndex(Map.of("i1", new byte[32]), "i1"))
        .withBean(
            DataKeyProvider.class,
            () -> new KeyringDataKeyProvider(Map.of("e1", mock(DataKeyProvider.class)), "e1"));
  }

  @Test
  void operationalVaultUsesExplicitClientAndNeverBorrowsRepositoryClient() {
    var operational = mock(DynamoDbClient.class);
    var repositories = mock(DynamoDbClient.class);
    when(operational.describeTable(any(DescribeTableRequest.class))).thenReturn(table());
    when(operational.describeTimeToLive(any(DescribeTimeToLiveRequest.class))).thenReturn(ttl());
    when(operational.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(controls());
    when(operational.getItem(any(GetItemRequest.class)))
        .thenThrow(SdkClientException.create("synthetic outage"));
    base()
        .withPropertyValues("axiz.tokenization.vault.dynamodb.table=central-vault")
        .withBean("axizTokenVaultDynamoClient", DynamoDbClient.class, () -> operational)
        .withBean("repositoryClient", DynamoDbClient.class, () -> repositories)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(DynamoDbTokenVault.class)
                  .doesNotHaveBean(DynamoDbAsyncTokenVault.class);
              assertThrows(
                  AxizException.class,
                  () -> context.getBean(TokenVault.class).keyState("identity"));
              var request = ArgumentCaptor.forClass(GetItemRequest.class);
              verify(operational).getItem(request.capture());
              assertThat(request.getValue().tableName()).isEqualTo("central-vault");
              assertThat(request.getValue().consistentRead()).isTrue();
              verifyNoInteractions(repositories);
            });
    base()
        .withPropertyValues("axiz.tokenization.vault.dynamodb.table=central-vault")
        .withBean("repositoryClient", DynamoDbClient.class, () -> mock(DynamoDbClient.class))
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void nativeStackUsesOnlyItsExplicitAsyncClient() {
    var operational = mock(DynamoDbAsyncClient.class);
    when(operational.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(table()));
    when(operational.describeTimeToLive(any(DescribeTimeToLiveRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(ttl()));
    when(operational.transactGetItems(any(TransactGetItemsRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(controls()));
    when(operational.getItem(any(GetItemRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("synthetic outage")));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(CONFIG)
        .withUserConfiguration(Properties.class)
        .withPropertyValues(VAULT_PROPERTIES)
        .withBean(BlindIndex.class, () -> new BlindIndex(Map.of("i1", new byte[32]), "i1"))
        .withBean(
            AsyncDataKeyProvider.class,
            () ->
                new KeyringAsyncDataKeyProvider(
                    Map.of("e1", mock(AsyncDataKeyProvider.class)), "e1"))
        .withPropertyValues("axiz.tokenization.vault.dynamodb.table=central-vault")
        .withBean("axizTokenVaultDynamoAsyncClient", DynamoDbAsyncClient.class, () -> operational)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(DynamoDbAsyncTokenVault.class)
                  .doesNotHaveBean(DynamoDbClient.class)
                  .doesNotHaveBean(TokenVault.class);
              assertThrows(
                  CompletionException.class,
                  () ->
                      context
                          .getBean(AsyncTokenVault.class)
                          .keyState("identity")
                          .toCompletableFuture()
                          .join());
              verify(operational).getItem(any(GetItemRequest.class));
            });
  }

  @Test
  void missingTableOrUnboundedDeadlineFailsWithoutCreatingInfrastructure() {
    var base =
        base()
            .withBean(
                "axizTokenVaultDynamoClient",
                DynamoDbClient.class,
                () -> mock(DynamoDbClient.class));
    base.run(context -> assertThat(context).hasFailed());
    base.withPropertyValues(
            "axiz.tokenization.vault.dynamodb.table=central-vault",
            "axiz.tokenization.vault.dynamodb.request-timeout=31s")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void unavailableReadinessCannotBecomeSuccessfulStartupOrLeakProviderDetails() {
    var client = mock(DynamoDbClient.class);
    when(client.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(SdkClientException.create("synthetic-credential-canary"));
    base()
        .withPropertyValues("axiz.tokenization.vault.dynamodb.table=central-vault")
        .withBean("axizTokenVaultDynamoClient", DynamoDbClient.class, () -> client)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageNotContaining("canary");
              assertThat(context.getStartupFailure()).hasNoCause();
            });
  }

  private static DescribeTableResponse table() {
    return DescribeTableResponse.builder()
        .table(
            TableDescription.builder()
                .tableName("central-vault")
                .tableStatus(TableStatus.ACTIVE)
                .keySchema(
                    KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pk")
                        .attributeType(ScalarAttributeType.S)
                        .build())
                .build())
        .build();
  }

  private static DescribeTimeToLiveResponse ttl() {
    return DescribeTimeToLiveResponse.builder()
        .timeToLiveDescription(
            TimeToLiveDescription.builder().timeToLiveStatus(TimeToLiveStatus.DISABLED).build())
        .build();
  }

  private static TransactGetItemsResponse controls() {
    return TransactGetItemsResponse.builder()
        .responses(
            ItemResponse.builder().item(control("C", "i1")).build(),
            ItemResponse.builder().item(control("E", "e1")).build())
        .build();
  }

  private static Map<String, AttributeValue> control(String kind, String key) {
    return Map.of(
        "pk",
        AttributeValue.fromS(kind + "#identity"),
        "schema",
        AttributeValue.fromN("1"),
        "keyset",
        AttributeValue.fromS("identity"),
        "version",
        AttributeValue.fromN("1"),
        "writeKey",
        AttributeValue.fromS(key),
        "readKeys",
        AttributeValue.fromL(List.of(AttributeValue.fromS(key))));
  }
}
