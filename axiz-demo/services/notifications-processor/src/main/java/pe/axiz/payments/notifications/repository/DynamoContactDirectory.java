package pe.axiz.payments.notifications.repository;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.kit.dynamodb.DynamoRepository;
import pe.axiz.spring.dynamodb.DynamoRepositories;

/** {@link ContactDirectory} sobre la tabla {@code notification-contacts}. */
@Component
class DynamoContactDirectory implements ContactDirectory {

  static final String TABLE = "notification-contacts";

  private final DynamoRepository<ContactItem> contacts;
  private final FieldEncryptor encryptor;

  DynamoContactDirectory(DynamoRepositories repositories, FieldEncryptor encryptor) {
    this.contacts = repositories.create(TABLE, ContactItem.class);
    this.encryptor = encryptor;
  }

  @Override
  public Optional<CustomerContact> find(String customerId) {
    return contacts.findById(customerId).map(item -> item.reveal(encryptor));
  }
}
