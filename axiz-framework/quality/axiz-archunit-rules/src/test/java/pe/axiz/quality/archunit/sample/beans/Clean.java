package pe.axiz.quality.archunit.sample.beans;

/** Una configuración con un nombre distinto del de sus beans. */
@Configuration
public class Clean {

  @Bean
  Object cleanJob() {
    return new Object();
  }
}
