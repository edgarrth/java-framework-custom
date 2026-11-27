package pe.axiz.payments.beneficiaries.controller;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import pe.axiz.core.security.AxizPrincipal;

/**
 * Autorización a nivel de objeto de los beneficiarios: un cliente solo lee y modifica sus propios
 * beneficiarios (el canal retransmite el token del cliente). Se rechazan los tokens de servicio:
 * ningún servicio gestiona beneficiarios en nombre de los clientes. Se usa desde
 * {@code @PreAuthorize}, por lo que se ejecuta previo a las repeticiones de idempotencia y a los
 * handlers.
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
