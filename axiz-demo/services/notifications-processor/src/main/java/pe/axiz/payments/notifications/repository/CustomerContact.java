package pe.axiz.payments.notifications.repository;

import org.jspecify.annotations.Nullable;
import pe.axiz.core.sensitive.Sensitive;
import pe.axiz.core.sensitive.SensitivityLevel;

/**
 * Preferencias de entrega descifradas que se usan en memoria. DynamoDB almacena {@link
 * ContactItem}, con la dirección cifrada y autenticada respecto del cliente y el canal.
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

  /** EMAIL, SMS o PUSH. */
  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  /**
   * Dirección de correo electrónico, número de teléfono o token del dispositivo, según el canal.
   */
  @Sensitive(level = SensitivityLevel.HIGH)
  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  /** Etiqueta de idioma BCP 47; si falta, se usa el valor por defecto del servicio. */
  public @Nullable String getLocale() {
    return locale;
  }

  public void setLocale(@Nullable String locale) {
    this.locale = locale;
  }

  /** Indica si el cliente acepta alertas de pagos. */
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
