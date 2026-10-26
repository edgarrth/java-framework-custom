package pe.axiz.payments.notifications.contact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.spring.dynamodb.DynamoRepositories;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
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

  final Scripted dynamo = new Scripted();
  final DynamoContactDirectory directory =
      new DynamoContactDirectory(
          new DynamoRepositories(
              DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), "local-"));

  @Test
  void readsTheContactOfTheCustomer() {
    dynamo.item =
        Map.of(
            "customerId", AttributeValue.fromS("C-1001"),
            "channel", AttributeValue.fromS("SMS"),
            "address", AttributeValue.fromS("+51999999999"),
            "locale", AttributeValue.fromS("es-PE"),
            "paymentAlerts", AttributeValue.fromBool(false));

    CustomerContact contact = directory.find("C-1001").orElseThrow();

    assertThat(contact.getCustomerId()).isEqualTo("C-1001");
    assertThat(contact.getChannel()).isEqualTo("SMS");
    assertThat(contact.getAddress()).isEqualTo("+51999999999");
    assertThat(contact.getLocale()).isEqualTo("es-PE");
    assertThat(contact.isPaymentAlerts()).isFalse();
    assertThat(contact).hasToString("CustomerContact[C-1001, SMS]");
    assertThat(dynamo.requests.get(0).tableName()).isEqualTo("local-notification-contacts");
    assertThat(dynamo.requests.get(0).key())
        .isEqualTo(Map.of("customerId", AttributeValue.fromS("C-1001")));
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
