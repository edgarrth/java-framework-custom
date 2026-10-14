package pe.axiz.payments.business.adapter.in.rest;

import java.time.ZoneOffset;
import pe.axiz.core.commons.Page;
import pe.axiz.payments.business.api.model.PaymentOrder;
import pe.axiz.payments.business.api.model.PaymentOrderPage;
import pe.axiz.payments.business.api.model.PaymentStatus;

/** Domain to API models. The creditor account always leaves masked. */
final class PaymentOrderApiMapper {

  private PaymentOrderApiMapper() {}

  static PaymentOrder toApi(pe.axiz.payments.business.domain.PaymentOrder o) {
    return new PaymentOrder()
        .id(o.id())
        .operationNumber(o.operationNumber())
        .customerId(o.customerId())
        .status(PaymentStatus.fromValue(o.status().name()))
        .amount(o.amount().amount())
        .currency(o.amount().currencyCode())
        .creditorCci(o.maskedCreditorCci())
        .processingDate(o.processingDate())
        .createdAt(o.createdAt().atOffset(ZoneOffset.UTC));
  }

  static PaymentOrderPage toApi(Page<pe.axiz.payments.business.domain.PaymentOrder> page) {
    return new PaymentOrderPage()
        .items(page.content().stream().map(PaymentOrderApiMapper::toApi).toList())
        .page(page.metadata().page())
        .size(page.metadata().size())
        .totalElements(page.metadata().totalElements());
  }
}
