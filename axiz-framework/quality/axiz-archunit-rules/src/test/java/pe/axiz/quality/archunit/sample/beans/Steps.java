package pe.axiz.quality.archunit.sample.beans;

/** Uses the names of other beans only as explicit names of its own beans: no clash with classes. */
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
