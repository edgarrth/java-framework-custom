package pe.axiz.payments.notifications.repository;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Decrypted delivery preferences used in memory. DynamoDB stores {@link ContactItem}, with the
 * address encrypted and authenticated against the customer and channel.
 */
public class CustomerContact {

  private String customerId = "";
  private String channel = "EMAIL";
  private String address = "";
  private @Nullable String locale;
  private boolean paymentAlerts = true;

  public CustomerContact() {}

  public CustomerContact(
      String customerId,
      String channel,
      String address,
      @Nullable String locale,
      boolean paymentAlerts) {
    this.customerId = customerId;
    this.channel = channel;
    this.address = address;
    this.locale = locale;
    this.paymentAlerts = paymentAlerts;
  }

  public String getCustomerId() {
    return customerId;
  }

  public void setCustomerId(String customerId) {
    this.customerId = customerId;
  }

  /** EMAIL, SMS or PUSH. */
  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  /** E-mail address, phone number or device token, depending on the channel. */
  @Sensitive(level = SensitivityLevel.HIGH)
  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  /** BCP 47 language tag; the service default when absent. */
  public @Nullable String getLocale() {
    return locale;
  }

  public void setLocale(@Nullable String locale) {
    this.locale = locale;
  }

  /** Whether the customer accepts payment alerts. */
  public boolean isPaymentAlerts() {
    return paymentAlerts;
  }

  public void setPaymentAlerts(boolean paymentAlerts) {
    this.paymentAlerts = paymentAlerts;
  }

  @Override
  public String toString() {
    return "CustomerContact[" + customerId + ", " + channel + "]";
  }
}
