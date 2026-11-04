package pe.axiz.payments.beneficiaries.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Object-level authorization of beneficiaries: a customer only reads and changes their own
 * beneficiaries (the channel relays the customer's token). Service tokens are refused: no service
 * manages beneficiaries on behalf of customers. Used from {@code @PreAuthorize}, so it runs before
 * idempotency replays and handlers.
 */
@Component("customerAccess")
public class CustomerAccess {

  public boolean isCustomer(@Nullable Authentication authentication, @Nullable String customerId) {
    return customerId != null
        && authentication != null
        && authentication.getPrincipal() instanceof AxizPrincipal caller
        && !caller.isMachine()
        && customerId.equals(caller.subject());
  }
}
