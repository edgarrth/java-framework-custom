package pe.axiz.payments.notifications.repository;

import java.util.Optional;

/** Contactos de notificación de los clientes. */
public interface ContactDirectory {

  Optional<CustomerContact> find(String customerId);
}
