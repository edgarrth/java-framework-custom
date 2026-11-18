package pe.axiz.payments.notifications.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pe.axiz.core.crypto.AesGcm;
import pe.axiz.core.crypto.CryptoException;
import pe.axiz.core.crypto.FieldEncryptor;
import pe.axiz.core.crypto.StaticKeyProvider;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/** Contacts read from the {@code notification-contacts} table by customer. */
class DynamoContactDirectoryTest {

  /** Answers one stored item, or none. */
  static final class Scripted implements DynamoDbClient {
    final List<GetItemRequest> requests = new ArrayList<>();
    Map<String, AttributeValue> item = Map.of();

    @Override
    public GetItemResponse getItem(GetItemRequest r) {
      requests.add(r);
      return item.isEmpty()
          ? GetItemResponse.builder().build()
          : GetItemResponse.builder().item(item).build();
    }

    @Override
    public String serviceName() {
      return "dynamodb";
    }

    @Override
    public void close() {}
  }

  final StaticKeyProvider keys = new StaticKeyProvider("v1", Map.of("v1", AesGcm.newKey()));
  final FieldEncryptor cipher = new FieldEncryptor(keys);
  final Scripted dynamo = new Scripted();

  @AfterEach
  void closeKeys() {
    keys.close();
  }

  final DynamoContactDirectory directory =
      new DynamoContactDirectory(
          new DynamoRepositories(
              DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), "local-"),
          cipher);

  @Test
  void readsTheContactOfTheCustomer() {
    var item =
        ContactItem.protect(
            new CustomerContact("C-1001", "SMS", "+51999999999", "es-PE", false), cipher);
    dynamo.item = TableSchema.fromBean(ContactItem.class).itemToMap(item, true);
    assertThat(dynamo.item.get("address").s()).doesNotContain("+51999999999");

    CustomerContact contact = directory.find("C-1001").orElseThrow();

    assertThat(contact.getCustomerId()).isEqualTo("C-1001");
    assertThat(contact.getChannel()).isEqualTo("SMS");
    assertThat(contact.getAddress()).isEqualTo("+51999999999");
    assertThat(contact.getLocale()).isEqualTo("es-PE");
    assertThat(contact.isPaymentAlerts()).isFalse();
    assertThat(contact).hasToString("CustomerContact[C-1001, SMS]");
    assertThat(dynamo.requests.getFirst().tableName()).isEqualTo("local-notification-contacts");
    assertThat(dynamo.requests.getFirst().key())
        .isEqualTo(Map.of("customerId", AttributeValue.fromS("C-1001")));
  }

  @Test
  void movingAnEncryptedAddressToAnotherCustomerOrChannelFails() {
    var item =
        ContactItem.protect(
            new CustomerContact("C-1001", "EMAIL", "ana@example.test", null, true), cipher);
    var schema = TableSchema.fromBean(ContactItem.class);
    item.setCustomerId("C-1002");
    dynamo.item = schema.itemToMap(item, true);
    assertThatThrownBy(() -> directory.find("C-1002")).isInstanceOf(CryptoException.class);
    item.setCustomerId("C-1001");
    item.setChannel("SMS");
    dynamo.item = schema.itemToMap(item, true);
    assertThatThrownBy(() -> directory.find("C-1001")).isInstanceOf(CryptoException.class);
  }

  @Test
  void legacyPlaintextAddressesFailClosed() {
    dynamo.item =
        Map.of(
            "customerId",
            AttributeValue.fromS("C-1001"),
            "channel",
            AttributeValue.fromS("EMAIL"),
            "address",
            AttributeValue.fromS("ana@example.test"));
    assertThatThrownBy(() -> directory.find("C-1001")).isInstanceOf(CryptoException.class);
  }

  @Test
  void aCustomerWithoutContactHasNone() {
    assertThat(directory.find("C-404")).isEmpty();
  }

  @Test
  void aNewContactAlertsByEmailByDefault() {
    CustomerContact contact = new CustomerContact();
    contact.setCustomerId("C-1");
    contact.setAddress("ana@example.test");
    contact.setLocale(null);

    assertThat(contact.getChannel()).isEqualTo("EMAIL");
    assertThat(contact.isPaymentAlerts()).isTrue();
    contact.setChannel("PUSH");
    contact.setPaymentAlerts(false);
    assertThat(contact.getChannel()).isEqualTo("PUSH");
    assertThat(contact.isPaymentAlerts()).isFalse();
  }
}
