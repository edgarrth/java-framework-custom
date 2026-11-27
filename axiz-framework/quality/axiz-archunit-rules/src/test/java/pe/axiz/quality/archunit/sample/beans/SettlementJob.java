package pe.axiz.quality.archunit.sample.beans;

/** La falla de arranque del batch de liquidación: la clase y su job comparten "settlementJob". */
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
