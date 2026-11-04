package pe.axiz.payments.orders.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.money.Money;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.CommandBus;
import pe.axiz.extension.cqrs.Query;
import pe.axiz.extension.cqrs.QueryBus;
import pe.axiz.payments.orders.api.model.PaymentOrder;
import pe.axiz.payments.orders.api.model.PaymentOrderPage;
import pe.axiz.payments.orders.api.model.PaymentOrderRequest;
import pe.axiz.payments.orders.api.model.PaymentStatus;
import pe.axiz.payments.orders.api.model.StatusChange;
import pe.axiz.payments.orders.application.command.ChangePaymentOrderStatusCommand;
import pe.axiz.payments.orders.application.command.CreatePaymentOrderCommand;
import pe.axiz.payments.orders.application.command.InMemoryPaymentOrders;
import pe.axiz.payments.orders.application.query.GetPaymentOrderHandler;
import pe.axiz.payments.orders.application.query.GetPaymentOrderQuery;
import pe.axiz.payments.orders.application.query.ListPaymentOrdersHandler;
import pe.axiz.payments.orders.application.query.ListPaymentOrdersQuery;

/**
 * The REST adapter: the commands it builds from the contract models and the contract models it
 * answers with (masked creditor account, UTC timestamps), queries through the real handlers.
 */
class PaymentOrdersControllerTest {

  final InMemoryPaymentOrders orders = new InMemoryPaymentOrders();
  final List<Command<?>> sent = new ArrayList<>();
  final GetPaymentOrderHandler get = new GetPaymentOrderHandler(orders);
  final ListPaymentOrdersHandler list = new ListPaymentOrdersHandler(orders);

  final CommandBus commands =
      new CommandBus() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R send(Command<R> command) {
          sent.add(command);
          pe.axiz.payments.orders.domain.PaymentOrder order =
              switch (command) {
                case CreatePaymentOrderCommand c -> stored("P-1", c.customerId());
                case ChangePaymentOrderStatusCommand c -> {
                  pe.axiz.payments.orders.domain.PaymentOrder o =
                      orders.find(c.orderId()).orElseThrow();
                  o.moveTo(c.status(), c.reason());
                  yield o;
                }
                default -> throw new IllegalArgumentException(command.toString());
              };
          return (R) order;
        }
      };
  final QueryBus queries =
      new QueryBus() {
        @Override
        @SuppressWarnings("unchecked")
        public <R> R ask(Query<R> query) {
          return switch (query) {
            case GetPaymentOrderQuery q -> (R) get.handle(q);
            case ListPaymentOrdersQuery q -> (R) list.handle(q);
            default -> throw new IllegalArgumentException(query.toString());
          };
        }
      };
  final PaymentOrdersController controller = new PaymentOrdersController(commands, queries);

  pe.axiz.payments.orders.domain.PaymentOrder stored(String id, String customer) {
    var order =
        pe.axiz.payments.orders.domain.PaymentOrder.register(
            id,
            "OP20261009-00000001",
            customer,
            "1910012345678",
            "00219100123456789053",
            Money.of("150.75", "PEN"),
            "Rent",
            LocalDate.of(2026, 10, 9),
            Instant.parse("2026-10-09T15:00:00Z"));
    orders.create(order, null);
    return order;
  }

  @Test
  void createSendsTheCommandWithTheKeyAndAnswers201WithTheLocation() {
    var response =
        controller.createPaymentOrder(
            "key-1",
            new PaymentOrderRequest()
                .customerId("C-1")
                .debtorAccount("1910012345678")
                .creditorCci("00219100123456789053")
                .amount(new BigDecimal("150.75"))
                .currency("PEN")
                .description("Rent"));

    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getHeaders().getLocation()).hasToString("/v1/payment-orders/P-1");
    PaymentOrder body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.getId()).isEqualTo("P-1");
    assertThat(body.getStatus()).isEqualTo(PaymentStatus.RECEIVED);
    assertThat(body.getCreditorCci()).isNotEqualTo("00219100123456789053").endsWith("9053");
    assertThat(body.getCreatedAt())
        .isEqualTo(Instant.parse("2026-10-09T15:00:00Z").atOffset(ZoneOffset.UTC));
    assertThat(sent)
        .singleElement()
        .isEqualTo(
            new CreatePaymentOrderCommand(
                "C-1",
                "1910012345678",
                "00219100123456789053",
                new BigDecimal("150.75"),
                "PEN",
                "Rent",
                "key-1"));
  }

  @Test
  void getAndListGoThroughTheQueries() {
    stored("P-7", "C-1");
    stored("P-8", "C-2");

    var one = controller.getPaymentOrder("P-7");
    assertThat(one.getStatusCode().value()).isEqualTo(200);
    assertThat(one.getBody()).isNotNull();
    assertThat(one.getBody().getCustomerId()).isEqualTo("C-1");

    PaymentOrderPage page = controller.listPaymentOrders("C-1", PageRequest.of(0, 20)).getBody();
    assertThat(page).isNotNull();
    assertThat(page.getItems()).extracting(PaymentOrder::getId).containsExactly("P-7");
    assertThat(page.getPage()).isZero();
    assertThat(page.getSize()).isEqualTo(20);
    assertThat(page.getTotalElements()).isEqualTo(1L);

    assertThatThrownBy(() -> controller.getPaymentOrder("P-404"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aStatusChangeIsSentInTheDomainStatus() {
    stored("P-9", "C-1");

    var response =
        controller.changePaymentOrderStatus(
            "P-9", new StatusChange().status(PaymentStatus.PROCESSING).reason("picked up"));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    assertThat(sent)
        .singleElement()
        .isEqualTo(
            new ChangePaymentOrderStatusCommand(
                "P-9", pe.axiz.payments.orders.domain.PaymentStatus.PROCESSING, "picked up"));
  }
}
