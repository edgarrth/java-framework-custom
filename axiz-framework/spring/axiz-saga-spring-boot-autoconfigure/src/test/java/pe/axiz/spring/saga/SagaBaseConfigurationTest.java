package pe.axiz.spring.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.saga.InMemorySagaStateStore;
import pe.axiz.core.saga.SagaOrchestrator;
import pe.axiz.core.saga.SagaStateStore;

class SagaBaseConfigurationTest {
  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizSagaAutoConfiguration.class));

  @Test
  void baseRequiresAStoreWithoutPullingAProvider() {
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore"));
    context.run(c -> assertThat(c).hasFailed());
    context
        .withBean(SagaStateStore.class, InMemorySagaStateStore::new)
        .run(c -> assertThat(c).hasSingleBean(SagaOrchestrator.class));
    context
        .withBean(SagaStateStore.class, InMemorySagaStateStore::new)
        .withPropertyValues("axiz.saga.context-protection=envelope")
        .run(c -> assertThat(c).hasFailed());
    context
        .withPropertyValues("axiz.saga.enabled=false")
        .run(c -> assertThat(c).doesNotHaveBean(SagaOrchestrator.class));
  }
}
