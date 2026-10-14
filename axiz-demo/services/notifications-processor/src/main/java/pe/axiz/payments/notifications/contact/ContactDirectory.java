package pe.axiz.payments.notifications.contact;

import java.util.Optional;

/** Notification contacts of customers. */
public interface ContactDirectory {

  Optional<CustomerContact> find(String customerId);
}
