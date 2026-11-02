package pe.axiz.spring.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.r2dbc.spi.ConnectionFactory;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import pe.axiz.core.sequence.AsyncSequenceStore;
import pe.axiz.core.sequence.SequenceStore;
import pe.axiz.kit.r2dbc.R2dbcSequenceStore;
import pe.axiz.kit.r2dbc.ReactiveSequenceStore;

class ReactiveRelationalSequenceTest {
  private static ConnectionFactory connections() {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    when(factory.create())
        .thenAnswer(ignored -> reactor.core.publisher.Mono.<io.r2dbc.spi.Connection>never());
    when(factory.getMetadata()).thenReturn(() -> "test");
    return factory;
  }

  private static ReactiveWebApplicationContextRunner reactive() {
    return new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizSequenceAutoConfiguration.class, AxizSequenceR2dbcAutoConfiguration.class));
  }

  @Test
  void defaultAndExplicitRelationalStoresSelectR2dbcInsteadOfJdbc() {
    ConnectionFactory connections = connections();
    for (String store : new String[] {"jdbc", "r2dbc"}) {
      reactive()
          .withBean(ConnectionFactory.class, () -> connections)
          .withPropertyValues("axiz.sequence.store=" + store)
          .run(
              context -> {
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveSequenceStore.class)
                    .hasSingleBean(ReactiveSequences.class)
                    .doesNotHaveBean(SequenceStore.class)
                    .doesNotHaveBean(Sequences.class)
                    .doesNotHaveBean(AsyncSequenceStore.class);
                assertThat(context.getBean(ReactiveSequenceStore.class))
                    .isInstanceOf(R2dbcSequenceStore.class);
              });
    }
  }

  @Test
  void userSuppliedNativeStoreTakesPrecedenceAndOtherStoresDoNotSelectRelationalWiring() {
    reactive()
        .withBean(ConnectionFactory.class, ReactiveRelationalSequenceTest::connections)
        .withBean(
            AsyncSequenceStore.class,
            () -> (name, period, amount) -> CompletableFuture.completedFuture(1L))
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(ReactiveSequences.class)
                    .doesNotHaveBean(ReactiveSequenceStore.class));
    reactive()
        .withBean(ConnectionFactory.class, ReactiveRelationalSequenceTest::connections)
        .withPropertyValues("axiz.sequence.store=other")
        .run(context -> assertThat(context).hasFailed());
    reactive()
        .withPropertyValues("axiz.sequence.store=r2dbc")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void rejectsUnsafeTableNamesAtStartup() {
    reactive()
        .withBean(ConnectionFactory.class, ReactiveRelationalSequenceTest::connections)
        .withPropertyValues("axiz.sequence.table=unsafe;DROP")
        .run(context -> assertThat(context).hasFailed());
  }
}
