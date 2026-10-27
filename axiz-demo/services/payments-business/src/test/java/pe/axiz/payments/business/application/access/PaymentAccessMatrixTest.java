package pe.axiz.payments.business.application.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.money.Money;
import pe.axiz.framework.cqrs.AccessPolicies;
import pe.axiz.framework.cqrs.Caller;
import pe.axiz.payments.business.application.command.ChangePaymentOrderStatusCommand;
import pe.axiz.payments.business.application.command.CreatePaymentOrderCommand;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.application.query.GetPaymentOrderQuery;
import pe.axiz.payments.business.application.query.ListPaymentOrdersQuery;
import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;

/**
 * Regression: two customers, two service identities and different scopes. Cross reads, orders on
 * behalf of another customer and status changes by an unauthorized actor are rejected.
 */
class PaymentAccessMatrixTest {

  static final Caller ANA = new Caller("C-1001", "payments-web", null, "iss", Set.of());
  static final Caller LUIS = new Caller("C-1002", "payments-web", null, "iss", Set.of());
  static final Caller PROCESS =
      new Caller(null, "payment-process", null, "iss", Set.of(PaymentAuthorities.PROCESS));
  static final Caller REPORTING =
      new Caller(null, "reporting", null, "iss", Set.of(PaymentAuthorities.READ));
  static final Caller OTHER_SERVICE = new Caller(null, "other", null, "iss", Set.of());

  static final PaymentOrder ANA_ORDER =
      PaymentOrder.register(
          "0190a1b2-0000-7000-8000-000000000001",
          "OP-1",
          "C-1001",
          "1910012345678",
          "00219100123456789053",
          Money.of("150.75", "PEN"),
          null,
          LocalDate.of(2026, 10, 12),
          Instant.EPOCH);

  static PaymentOrders orders() {
    return (PaymentOrders)
        Proxy.newProxyInstance(
            PaymentOrders.class.getClassLoader(),
            new Class<?>[] {PaymentOrders.class},
            (proxy, method, args) -> {
              if (method.getName().equals("find")) {
                return ANA_ORDER.id().equals(args[0]) ? Optional.of(ANA_ORDER) : Optional.empty();
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  final AccessPolicies policies =
      new AccessPolicies(
          List.of(
              new CreatePaymentOrderAccess(),
              new ChangePaymentOrderStatusAccess(),
              new GetPaymentOrderAccess(orders()),
              new ListPaymentOrdersAccess()));

  static CreatePaymentOrderCommand create(String customerId) {
    return new CreatePaymentOrderCommand(
        customerId, "1910012345678", "00219100123456789053", BigDecimal.TEN, "PEN", null, "key-1");
  }

  @Test
  void customersOnlyCreateTheirOwnOrders() {
    assertThatCode(() -> policies.check(create("C-1001"), ANA)).doesNotThrowAnyException();
    assertThatThrownBy(() -> policies.check(create("C-1001"), LUIS))
        .isInstanceOf(AccessException.class);
    assertThatThrownBy(() -> policies.check(create("C-1001"), PROCESS))
        .isInstanceOf(AccessException.class);
    assertThatThrownBy(() -> policies.check(create("C-1001"), OTHER_SERVICE))
        .isInstanceOf(AccessException.class);
  }

  @Test
  void onlyThePaymentProcessChangesStatuses() {
    var change = new ChangePaymentOrderStatusCommand(ANA_ORDER.id(), PaymentStatus.REJECTED, "no");
    assertThatCode(() -> policies.check(change, PROCESS)).doesNotThrowAnyException();
    for (Caller denied : List.of(ANA, LUIS, REPORTING, OTHER_SERVICE)) {
      assertThatThrownBy(() -> policies.check(change, denied))
          .as(String.valueOf(denied.clientId()) + "/" + denied.subject())
          .isInstanceOf(AccessException.class);
    }
  }

  @Test
  void anotherCustomersOrderIsNotFound() {
    var get = new GetPaymentOrderQuery(ANA_ORDER.id());
    assertThatCode(() -> policies.check(get, ANA)).doesNotThrowAnyException();
    assertThatCode(() -> policies.check(get, PROCESS)).doesNotThrowAnyException();
    assertThatCode(() -> policies.check(get, REPORTING)).doesNotThrowAnyException();
    assertThatThrownBy(() -> policies.check(get, LUIS)).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> policies.check(get, OTHER_SERVICE))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> policies.check(new GetPaymentOrderQuery("missing"), ANA))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void customersListOnlyTheirOwnOrders() {
    var list = new ListPaymentOrdersQuery("C-1001", PageRequest.of(0, 20));
    assertThatCode(() -> policies.check(list, ANA)).doesNotThrowAnyException();
    assertThatCode(() -> policies.check(list, REPORTING)).doesNotThrowAnyException();
    assertThatThrownBy(() -> policies.check(list, LUIS)).isInstanceOf(AccessException.class);
    assertThatThrownBy(() -> policies.check(list, OTHER_SERVICE))
        .isInstanceOf(AccessException.class);
  }

  @Test
  void everyCommandAndQueryOfTheServiceIsProtected() {
    assertThat(
            policies.unprotected(
                List.of(
                    CreatePaymentOrderCommand.class,
                    ChangePaymentOrderStatusCommand.class,
                    GetPaymentOrderQuery.class,
                    ListPaymentOrdersQuery.class)))
        .isEmpty();
  }
}
