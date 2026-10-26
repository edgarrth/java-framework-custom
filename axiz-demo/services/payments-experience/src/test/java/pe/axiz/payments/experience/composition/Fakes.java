package pe.axiz.payments.experience.composition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;
import pe.axiz.payments.experience.client.beneficiariescrud.BeneficiariesCrudClient;
import pe.axiz.payments.experience.client.beneficiariescrud.model.Beneficiary;
import pe.axiz.payments.experience.client.beneficiariescrud.model.BeneficiaryPage;
import pe.axiz.payments.experience.client.beneficiariescrud.model.BeneficiaryRequest;
import pe.axiz.payments.experience.client.beneficiariescrud.model.BeneficiaryUpdate;
import pe.axiz.payments.experience.client.paymentsbusiness.PaymentsBusinessClient;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrder;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrderPage;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrderRequest;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentStatus;
import pe.axiz.payments.experience.client.paymentsbusiness.model.StatusChange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** In-memory downstream services for composition and controller tests. */
public final class Fakes {

  public static final String CCI = "00219100123456789053";

  private Fakes() {}

  public static Context signedIn(String customerId) {
    RequestMetadata m =
        RequestMetadata.builder("corr-1", "req-1").subject(customerId).channel("web").build();
    return Context.of(RequestContext.KEY, m);
  }

  static ExternalServiceException downstream(int status, @Nullable String code) {
    return ExternalServiceException.from(
        new ExternalErrorDetail("payments-business", status, code, "rejected", "{}"));
  }

  public static final class Crud implements BeneficiariesCrudClient {
    final Map<String, Beneficiary> rows = new HashMap<>();
    int listCalls;
    @Nullable RuntimeException listFailure;

    public Crud with(String customerId, String id, String alias, String cci, String currency) {
      rows.put(
          customerId + "/" + id,
          new Beneficiary(id, customerId, alias, "Ana Torres", cci, currency));
      return this;
    }

    @Override
    public Mono<BeneficiaryPage> listBeneficiaries(
        String customerId, @Nullable String cursor, @Nullable Integer size) {
      listCalls++;
      if (listFailure != null) {
        return Mono.error(listFailure);
      }
      List<Beneficiary> items = new ArrayList<>();
      rows.values().stream().filter(b -> customerId.equals(b.customerId())).forEach(items::add);
      return Mono.just(new BeneficiaryPage(items, null));
    }

    @Override
    public Mono<Beneficiary> createBeneficiary(
        String customerId, String idempotencyKey, BeneficiaryRequest body) {
      return Mono.error(new UnsupportedOperationException());
    }

    @Override
    public Mono<Beneficiary> getBeneficiary(String customerId, String beneficiaryId) {
      Beneficiary b = rows.get(customerId + "/" + beneficiaryId);
      return b == null ? Mono.error(downstream(404, "AXZ-CORE-0404")) : Mono.just(b);
    }

    @Override
    public Mono<Beneficiary> updateBeneficiary(
        String customerId, String beneficiaryId, BeneficiaryUpdate body) {
      return Mono.error(new UnsupportedOperationException());
    }

    @Override
    public Mono<Void> deleteBeneficiary(String customerId, String beneficiaryId) {
      return Mono.error(new UnsupportedOperationException());
    }
  }

  public static final class Business implements PaymentsBusinessClient {
    final Map<String, PaymentOrder> orders = new HashMap<>();
    final Map<String, PaymentOrderRequest> received = new HashMap<>();
    @Nullable RuntimeException failure;

    @Override
    public Mono<PaymentOrder> createPaymentOrder(String idempotencyKey, PaymentOrderRequest body) {
      if (failure != null) {
        return Mono.error(failure);
      }
      received.put(idempotencyKey, body);
      PaymentOrder order =
          order("P-" + received.size(), body.customerId(), body.amount(), body.currency());
      orders.put(order.id(), order);
      return Mono.just(order);
    }

    @Override
    public Mono<PaymentOrderPage> listPaymentOrders(
        String customerId, @Nullable Integer page, @Nullable Integer size) {
      return Mono.error(new UnsupportedOperationException());
    }

    @Override
    public Mono<PaymentOrder> getPaymentOrder(String orderId) {
      PaymentOrder o = orders.get(orderId);
      return o == null ? Mono.error(downstream(404, "AXZ-CORE-0404")) : Mono.just(o);
    }

    @Override
    public Mono<PaymentOrder> changePaymentOrderStatus(
        String orderId, String idempotencyKey, StatusChange body) {
      return Mono.error(new UnsupportedOperationException());
    }

    static PaymentOrder order(String id, String customerId, BigDecimal amount, String currency) {
      return new PaymentOrder(
          id,
          "OP20261012-00000001",
          customerId,
          PaymentStatus.RECEIVED,
          amount,
          currency,
          "****************9053",
          LocalDate.of(2026, 10, 12),
          null);
    }
  }
}
