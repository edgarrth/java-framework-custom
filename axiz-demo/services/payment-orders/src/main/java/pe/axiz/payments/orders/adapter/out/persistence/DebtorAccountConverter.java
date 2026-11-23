package pe.axiz.payments.orders.adapter.out.persistence;

import jakarta.persistence.Converter;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.kit.jpa.EncryptedConverter;

/** Vincula el texto cifrado de la cuenta con su columna de pago específica. */
@Converter
public final class DebtorAccountConverter extends EncryptedConverter {
  public DebtorAccountConverter(FieldEncryptor encryptor) {
    super(encryptor);
  }

  @Override
  protected String context() {
    return "payment_order.debtor_account";
  }
}
