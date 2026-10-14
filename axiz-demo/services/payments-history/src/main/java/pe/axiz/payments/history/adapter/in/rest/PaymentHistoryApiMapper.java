package pe.axiz.payments.history.adapter.in.rest;

import java.time.ZoneOffset;
import pe.axiz.core.commons.Page;
import pe.axiz.core.money.Money;
import pe.axiz.payments.history.api.model.PaymentHistoryItem;
import pe.axiz.payments.history.api.model.PaymentHistoryPage;
import pe.axiz.payments.history.api.model.PaymentStatus;
import pe.axiz.payments.history.domain.PaymentHistory;

/** Domain to API models. The creditor account always leaves masked. */
final class PaymentHistoryApiMapper {

  private PaymentHistoryApiMapper() {}

  static PaymentHistoryItem toApi(PaymentHistory h) {
    Money amount = h.amount();
    return new PaymentHistoryItem()
        .paymentId(h.paymentId())
        .operationNumber(h.operationNumber())
        .customerId(h.customerId())
        .status(PaymentStatus.fromValue(h.status().name()))
        .reason(h.reason())
        .amount(amount == null ? null : amount.amount())
        .currency(amount == null ? null : amount.currencyCode())
        .creditorCci(h.maskedCreditorCci())
        .processingDate(h.processingDate())
        .lastChangeAt(h.lastChangeAt().atOffset(ZoneOffset.UTC));
  }

  static PaymentHistoryPage toApi(Page<PaymentHistory> page) {
    return new PaymentHistoryPage()
        .items(page.content().stream().map(PaymentHistoryApiMapper::toApi).toList())
        .page(page.metadata().page())
        .size(page.metadata().size())
        .totalElements(page.metadata().totalElements());
  }
}
