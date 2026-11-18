package pe.axiz.payments.notifications.repository;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.crypto.EncryptedValue;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.kit.dynamodb.EncryptedValueAttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/** Storage mapping: plaintext delivery addresses never enter DynamoDB. */
@DynamoDbBean
public class ContactItem {
  private String customerId = "";
  private String channel = "EMAIL";
  private @Nullable EncryptedValue address;
  private @Nullable String locale;
  private boolean paymentAlerts = true;

  public ContactItem() {}

  /** Used by the customer profile writer (and the explicit local demo seed). */
  public static ContactItem protect(CustomerContact contact, FieldEncryptor cipher) {
    var item = new ContactItem();
    item.customerId = contact.getCustomerId();
    item.channel = contact.getChannel();
    item.locale = contact.getLocale();
    item.paymentAlerts = contact.isPaymentAlerts();
    item.address =
        EncryptedValue.parse(
            Objects.requireNonNull(
                cipher.encrypt(contact.getAddress(), "notification.address", item.binding())));
    return item;
  }

  CustomerContact reveal(FieldEncryptor cipher) {
    return new CustomerContact(
        customerId,
        channel,
        Objects.requireNonNull(
            cipher.decrypt(
                Objects.requireNonNull(address, "Encrypted address is required").encoded(),
                "notification.address",
                binding())),
        locale,
        paymentAlerts);
  }

  private String binding() {
    return customerId.length() + ":" + customerId + channel;
  }

  @DynamoDbPartitionKey
  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  @DynamoDbConvertedBy(EncryptedValueAttributeConverter.class)
  public @Nullable EncryptedValue getAddress() {
    return address;
  }

  public void setAddress(@Nullable EncryptedValue address) {
    this.address = address;
  }

  public @Nullable String getLocale() {
    return locale;
  }

  public void setLocale(@Nullable String locale) {
    this.locale = locale;
  }

  public boolean isPaymentAlerts() {
    return paymentAlerts;
  }

  public void setPaymentAlerts(boolean paymentAlerts) {
    this.paymentAlerts = paymentAlerts;
  }
}
