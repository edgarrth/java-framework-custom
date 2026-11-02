package pe.axiz.spring.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.IdempotencyStore;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Which store and which filter a service gets, and where the in-memory store is refused. */
class IdempotencyAutoConfigurationTest {

  private final WebApplicationContextRunner servlet =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizIdempotencyAutoConfiguration.class));

  private final ReactiveWebApplicationContextRunner reactive =
      new ReactiveWebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizIdempotencyAutoConfiguration.class));

  @Test
  void aLocalServletServiceGetsTheMemoryStoreAndTheServletFilter() {
    servlet
        .withPropertyValues("axiz.config.environment=local", "axiz.idempotency.store=memory")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(IdempotencyService.class);
              assertThat(ctx.getBean(IdempotencyStore.class))
                  .isInstanceOf(InMemoryIdempotencyStore.class);
              assertThat(ctx).hasSingleBean(IdempotencyFilter.class);
              assertThat(ctx).doesNotHaveBean(IdempotencyWebFilter.class);
              assertThat(ctx).hasSingleBean(IdempotencyReplayHandler.class);
              assertThat(ctx.getBean("axizIdempotencyAdvisor")).isInstanceOf(Advisor.class);
            });
  }

  @Test
  void aReactiveServiceGetsTheWebFilter() {
    reactive
        .withPropertyValues("axiz.config.environment=test", "axiz.idempotency.store=memory")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(IdempotencyWebFilter.class);
              assertThat(ctx).doesNotHaveBean(IdempotencyFilter.class);
            });
  }

  @Test
  void theMemoryStoreIsRefusedWhereSeveralReplicasRun() {
    servlet
        .withPropertyValues("axiz.config.environment=prod", "axiz.idempotency.store=memory")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void theMemoryStoreIsRefusedWhenTheEnvironmentIsNotSet() {
    servlet
        .withPropertyValues("axiz.idempotency.store=memory")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.config.environment is not set"));
  }

  @Test
  void aStoreWithoutItsClientStopsTheStartupWithTheSettingToFix() {
    servlet
        .withPropertyValues("axiz.config.environment=local", "axiz.idempotency.store=redis")
        .run(
            ctx ->
                assertThat(ctx)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("axiz.idempotency.store=redis is not available"));
  }

  @Test
  void theDynamoDbStoreIsUsedWhenChosen() {
    servlet
        .withConfiguration(AutoConfigurations.of(AxizIdempotencyDynamoDbAutoConfiguration.class))
        .withPropertyValues(
            "axiz.idempotency.store=dynamodb",
            "axiz.aws.region=us-east-1",
            "axiz.aws.endpoint=http://localhost:4566")
        .run(
            ctx -> {
              assertThat(ctx.getBean(IdempotencyStore.class))
                  .isInstanceOf(DynamoDbIdempotencyStore.class);
              assertThat(ctx).hasSingleBean(DynamoDbClient.class);
            });
  }

  @Test
  void itCanBeTurnedOff() {
    servlet
        .withPropertyValues("axiz.idempotency.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(IdempotencyService.class));
  }
}
