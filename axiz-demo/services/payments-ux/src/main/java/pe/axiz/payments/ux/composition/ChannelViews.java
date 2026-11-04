package pe.axiz.payments.ux.composition;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.MaskType;
import pe.axiz.core.sensitive.Masker;
import pe.axiz.payments.ux.api.model.BeneficiaryList;
import pe.axiz.payments.ux.api.model.BeneficiaryView;
import pe.axiz.payments.ux.api.model.PaymentView;
import pe.axiz.payments.ux.client.beneficiaries.model.Beneficiary;
import pe.axiz.payments.ux.client.beneficiaries.model.BeneficiaryPage;
import pe.axiz.payments.ux.client.paymentorders.model.PaymentOrder;

/**
 * Shapes downstream data for the channels: account numbers keep their last 4 digits and holder
 * names their initials, whatever the downstream service returned.
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

  /** Last 4 digits; values already masked downstream keep their shape. */
  static @Nullable String account(@Nullable String value) {
    return value == null ? null : Masker.mask(value, MaskType.DOCUMENT);
  }

  /** Last 4 characters of an account, masked or not. */
  static String lastFour(String value) {
    return value.length() <= 4 ? value : value.substring(value.length() - 4);
  }
}
