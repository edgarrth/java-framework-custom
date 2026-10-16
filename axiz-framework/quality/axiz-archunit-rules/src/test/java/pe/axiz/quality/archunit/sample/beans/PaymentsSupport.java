package pe.axiz.quality.archunit.sample.beans;

/** A component whose explicit name collides with the {@code @Bean} method {@code payments()}. */
@Component("payments")
public class PaymentsSupport {}
