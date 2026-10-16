package pe.axiz.quality.archunit.sample.beans;

/** The startup failure of the settlement batch: the class and its job share "settlementJob". */
@Configuration
public class SettlementJob {

  @Bean
  Object settlementJob() {
    return new Object();
  }

  @Bean
  Object loadStep() {
    return new Object();
  }
}
