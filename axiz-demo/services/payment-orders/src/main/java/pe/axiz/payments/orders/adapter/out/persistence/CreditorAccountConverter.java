package pe.axiz.payments.orders.adapter.out.persistence;

import jakarta.persistence.Converter;
import pe.axiz.kit.jpa.EncryptedConverter;

/** Binds the account ciphertext to its specific payment column. */
@Converter
public final class CreditorAccountConverter extends EncryptedConverter {
  @Override
  protected String context() {
    return "payment_order.creditor_cci";
  }
}
