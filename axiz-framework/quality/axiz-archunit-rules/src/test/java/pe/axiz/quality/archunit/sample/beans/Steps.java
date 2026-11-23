package pe.axiz.quality.archunit.sample.beans;

/**
 * Usa los nombres de otros beans solo como nombres explícitos de sus propios beans: sin choque con
 * las clases.
 */
@Configuration("stepsConfiguration")
public class Steps {

  @Bean(name = "summaryStep")
  Object summary() {
    return new Object();
  }

  @Bean
  Object payments() {
    return new Object();
  }
}
