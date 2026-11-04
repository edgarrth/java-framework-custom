package pe.axiz.spring.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.autoconfigure.metrics.DataRepositoryMetricsAutoConfiguration;
import org.springframework.boot.data.metrics.MetricsRepositoryMethodInvocationListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.data.repository.core.support.RepositoryMethodInvocationListener.RepositoryMethodInvocation;
import org.springframework.data.repository.core.support.RepositoryMethodInvocationListener.RepositoryMethodInvocationResult;
import org.springframework.data.repository.core.support.RepositoryMethodInvocationListener.RepositoryMethodInvocationResult.State;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.function.SingletonSupplier;

class RepositoryMetricsStartupTest {
  interface Payments {
    void find();
  }

  @Test
  void registryIsAlreadyResolvedBeforeTheFirstReactiveRepositoryCompletion() throws Exception {
    var result = mock(RepositoryMethodInvocationResult.class);
    when(result.getState()).thenReturn(State.SUCCESS);
    var invocation =
        new RepositoryMethodInvocation(
            Payments.class,
            Payments.class.getMethod("find"),
            result,
            TimeUnit.MILLISECONDS.toNanos(5));
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMetricsAutoConfiguration.class, DataRepositoryMetricsAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues("management.metrics.data.repository.metric-name=custom.repositories")
        .run(
            context -> {
              assertThat(context).hasSingleBean(MetricsRepositoryMethodInvocationListener.class);
              assertThat(context).hasBean("axizReactiveRepositoryMetricsListener");
              var listener = context.getBean(MetricsRepositoryMethodInvocationListener.class);
              var registry = context.getBean(MeterRegistry.class);
              var supplier =
                  (SingletonSupplier<?>) ReflectionTestUtils.getField(listener, "registrySupplier");
              // Inspect readiness without calling get(): invoking it would hide lazy
              // initialization.
              assertThat(ReflectionTestUtils.getField(supplier, "singletonInstance"))
                  .isSameAs(registry);
              listener.afterInvocation(invocation);
              assertThat(registry.get("custom.repositories").timer().count()).isEqualTo(1);
            });
  }

  @Test
  void servletKeepsTheBootListenerAndAnApplicationListenerIsRespected() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMetricsAutoConfiguration.class, DataRepositoryMetricsAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context ->
                assertThat(context).doesNotHaveBean("axizReactiveRepositoryMetricsListener"));
    var custom = mock(MetricsRepositoryMethodInvocationListener.class);
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizMetricsAutoConfiguration.class, DataRepositoryMetricsAutoConfiguration.class))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(MetricsRepositoryMethodInvocationListener.class, () -> custom)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MetricsRepositoryMethodInvocationListener.class);
              assertThat(context.getBean(MetricsRepositoryMethodInvocationListener.class))
                  .isSameAs(custom);
            });
  }
}
