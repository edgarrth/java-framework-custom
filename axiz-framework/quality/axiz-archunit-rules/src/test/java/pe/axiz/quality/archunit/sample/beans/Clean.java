package pe.axiz.quality.archunit.sample.beans;

/** A configuration named apart from its beans. */
@Configuration
public class Clean {

  @Bean
  Object cleanJob() {
    return new Object();
  }
}
