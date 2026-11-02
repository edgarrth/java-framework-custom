package pe.axiz.payments.notifications.contact;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pe.axiz.kit.dynamodb.DynamoRepository;
import pe.axiz.spring.dynamodb.DynamoRepositories;

/** {@link ContactDirectory} on the {@code notification-contacts} table. */
@Component
class DynamoContactDirectory implements ContactDirectory {

  static final String TABLE = "notification-contacts";

  private final DynamoRepository<CustomerContact> contacts;

  DynamoContactDirectory(DynamoRepositories repositories) {
    this.contacts = repositories.create(TABLE, CustomerContact.class);
  }

  @Override
  public Optional<CustomerContact> find(String customerId) {
    return contacts.findById(customerId);
  }
}
