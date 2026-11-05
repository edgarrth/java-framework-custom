package pe.axiz.payments.ux.client.paymentorders.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * PaymentOrderPage.
 *
 * @param items items
 * @param pageValue page
 * @param size size
 * @param totalElements totalElements
 */
public record PaymentOrderPage(
    @Nullable List<PaymentOrder> items,
    @JsonProperty("page") @Nullable Integer pageValue,
    @Nullable Integer size,
    @Nullable Long totalElements) {
  public PaymentOrderPage {
    items = items == null ? null : List.copyOf(items);
  }
}
