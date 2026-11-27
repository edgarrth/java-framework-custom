package pe.axiz.quality.archunit.sample.guarded.adapter.in.web;

/** Un record de transporte cuyo monto pierde centavos. */
public record PaymentRequest(String beneficiaryId, double amount) {

  public static PaymentRequest of(String beneficiaryId, double amount) {
    return new PaymentRequest(beneficiaryId, amount);
  }
}
