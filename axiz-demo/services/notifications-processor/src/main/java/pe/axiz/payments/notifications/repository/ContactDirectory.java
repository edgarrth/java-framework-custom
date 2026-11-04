package pe.axiz.payments.notifications.repository;

import java.util.Optional;

/** Notification contacts of customers. */
public interface ContactDirectory {

  Optional<CustomerContact> find(String customerId);
}
