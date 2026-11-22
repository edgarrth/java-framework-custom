package pe.axiz.quality.archunit.sample.guarded.adapter.in.web;

/** A transport record whose amount loses cents. */
public record PaymentRequest(String beneficiaryId, double amount) {

  public static PaymentRequest of(String beneficiaryId, double amount) {
    return new PaymentRequest(beneficiaryId, amount);
  }
}
