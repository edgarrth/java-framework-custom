package pe.axiz.initializr.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The container, not a {@code new} in a test, builds the service: a second constructor once made
 * the production jar fail at startup while every unit test kept passing.
 */
class GenerationServiceWiringTest {

  @Test
  void theContainerBuildsTheProductionService() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
      context.register(GenerationService.class);
      context.refresh();
      assertThat(context.getBean(GenerationService.class).catalog().archetypes()).isNotEmpty();
    }
  }
}
