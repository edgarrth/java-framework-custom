package pe.axiz.quality.archunit.sample.beans;

/** Auto-configurations are registered by fully qualified name: never part of the check. */
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
