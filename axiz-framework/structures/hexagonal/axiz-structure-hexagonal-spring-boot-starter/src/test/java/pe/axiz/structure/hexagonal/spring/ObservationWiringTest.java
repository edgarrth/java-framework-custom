package pe.axiz.structure.hexagonal.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The use case advisors are created before bean post-processors run. Resolving the observation
 * registry at that point would leave it without its handlers: no tracing for the whole service.
 */
class ObservationWiringTest {

  @Test
  void theObservationRegistryKeepsItsHandlersWhenTheUseCaseAdvisorsExist() {
    List<String> observed = new CopyOnWriteArrayList<>();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AopAutoConfiguration.class,
                ObservationAutoConfiguration.class,
                AxizHexagonalAutoConfiguration.class,
                AxizReactiveHexagonalAutoConfiguration.class))
        .withPropertyValues("axiz.config.environment=test")
        .withBean(
            ObservationHandler.class,
            () ->
                new ObservationHandler<Observation.Context>() {
                  @Override
                  public void onStart(Observation.Context context) {
                    observed.add(context.getName());
                  }

                  @Override
                  public boolean supportsContext(Observation.Context context) {
                    return true;
                  }
                })
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              Observation.start("probe", context.getBean(ObservationRegistry.class)).stop();
              assertThat(observed).contains("probe");
            });
  }
}
