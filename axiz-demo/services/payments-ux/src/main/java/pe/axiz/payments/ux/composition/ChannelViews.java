package pe.axiz.payments.ux.composition;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Masker;
import pe.axiz.payments.ux.client.beneficiaries.model.Beneficiary;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryPage;
import pe.axiz.payments.ux.client.paymentorders.model.PaymentOrder;
import pe.axiz.payments.ux.composition.model.BeneficiaryList;
import pe.axiz.payments.ux.composition.model.BeneficiaryView;
import pe.axiz.payments.ux.composition.model.PaymentView;

/**
 * Adapta los datos aguas abajo para los canales: los números de cuenta conservan sus últimos 4
 * dígitos y los nombres de los titulares se enmascaran por completo, sin importar lo que devolvió
 * el servicio aguas abajo.
 */
final class ChannelViews {

  private ChannelViews() {}

  static PaymentView payment(PaymentOrder order, @Nullable String beneficiaryAlias) {
    var status = order.status();
    return new PaymentView()
        .paymentId(order.id())
        .operationNumber(order.operationNumber())
        .status(status == null ? null : status.name())
        .amount(order.amount())
        .currency(order.currency())
        .beneficiaryAlias(beneficiaryAlias)
        .creditorCci(account(order.creditorCci()))
        .processingDate(order.processingDate());
  }

  static BeneficiaryList beneficiaries(BeneficiaryPage page) {
    List<Beneficiary> items = Objects.requireNonNullElse(page.items(), List.of());
    return new BeneficiaryList()
        .items(items.stream().map(ChannelViews::beneficiary).toList())
        .nextCursor(page.nextCursor());
  }

  static BeneficiaryView beneficiary(Beneficiary b) {
    return new BeneficiaryView()
        .id(b.id())
        .alias(b.alias())
        .holderName(b.holderName() == null ? null : Masker.mask(b.holderName(), MaskType.NAME))
        .creditorCci(account(b.cci()))
        .currency(b.currency());
  }

  /** Últimos 4 dígitos; los valores ya enmascarados aguas abajo conservan su forma. */
  static @Nullable String account(@Nullable String value) {
    return value == null ? null : Masker.mask(value, MaskType.DOCUMENT);
  }

  /** Últimos 4 caracteres de una cuenta, enmascarada o no. */
  static String lastFour(String value) {
    return value.length() <= 4 ? value : value.substring(value.length() - 4);
  }
}
