package pe.axiz.quality.archunit.sample.beans;

/**
 * Las autoconfiguraciones se registran por nombre totalmente calificado: nunca forman parte de la
 * verificación.
 */
@AutoConfiguration
public class JobsAutoConfiguration {

  @Bean
  Object jobsAutoConfiguration() {
    return new Object();
  }

  @Configuration
  static class Nested {
    @Bean
    Object nested() {
      return new Object();
    }
  }
}
