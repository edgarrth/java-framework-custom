package pe.axiz.initializr.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * El contenedor, y no un {@code new} en una prueba, construye el servicio: un segundo constructor
 * haría fallar el jar de producción al arrancar mientras todas las pruebas unitarias siguen
 * pasando.
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
