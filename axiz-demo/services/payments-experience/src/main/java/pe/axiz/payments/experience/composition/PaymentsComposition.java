package pe.axiz.payments.experience.composition;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.featureflags.FeatureFlags;
import pe.axiz.core.money.Money;
import pe.axiz.structure.bff.spring.ReactiveComposer;
import pe.axiz.payments.experience.api.model.PaymentRequest;
import pe.axiz.payments.experience.api.model.PaymentView;
import pe.axiz.payments.experience.client.beneficiariescrud.model.Beneficiary;
import pe.axiz.payments.experience.client.paymentsbusiness.PaymentsBusinessClient;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrder;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrderRequest;
import reactor.core.publisher.Mono;

/**
 * Payment operations of the channels. Paying reads the beneficiary fresh (it must still exist and
 * match the currency), checks the currency flag and registers the order in payments-business with
 * the caller's Idempotency-Key, so a retried channel request never creates two orders. Reading a
 * payment composes the order (required) with the beneficiary alias (optional: the screen still
 * works if beneficiaries-crud is slow or down).
 */
@Component
public class PaymentsComposition {

  /** Flag that enables payments in US dollars (PEN is always enabled). */
  static final String USD_FLAG = "payments-usd";

  private final PaymentsBusinessClient business;
  private final BeneficiaryDirectory directory;
  private final FeatureFlags flags;
  private final Duration timeout;

  public PaymentsComposition(
      PaymentsBusinessClient business,
      BeneficiaryDirectory directory,
      FeatureFlags flags,
      @Value("${axiz.bff.timeout:3s}") Duration timeout) {
    this.business = business;
    this.directory = directory;
    this.flags = flags;
    this.timeout = timeout;
  }

  /** Pays a frequent beneficiary of the signed-in customer. */
  public Mono<PaymentView> createPayment(String idempotencyKey, PaymentRequest body) {
    return Customer.current()
        .flatMap(
            customer -> {
              if (!currencyEnabled(customer, body.getCurrency())) {
                return Mono.error(
                    new BusinessException(
                        ChannelErrors.CURRENCY_NOT_AVAILABLE,
                        "currency flag off",
                        Map.of("currency", body.getCurrency())));
              }
              return directory
                  .get(customer.id(), body.getBeneficiaryId())
                  .flatMap(b -> register(customer, idempotencyKey, body, b));
            });
  }

  /** A payment of the signed-in customer with its beneficiary alias (when available). */
  public Mono<PaymentView> getPayment(String paymentId) {
    return Customer.current()
        .flatMap(
            customer ->
                business
                    .getPaymentOrder(paymentId)
                    .onErrorMap(e -> DownstreamErrors.translate(e, "payment", paymentId))
                    // another customer's payment is reported as not found, never as forbidden
                    .filter(order -> customer.id().equals(order.customerId()))
                    .switchIfEmpty(Mono.error(() -> new NotFoundException("payment", paymentId)))
                    .flatMap(order -> withAlias(customer, order)));
  }

  private Mono<PaymentView> register(
      Customer customer, String idempotencyKey, PaymentRequest body, Beneficiary beneficiary) {
    if (!body.getCurrency().equals(beneficiary.currency())) {
      return Mono.error(
          new BusinessException(
              ChannelErrors.CURRENCY_MISMATCH,
              "currency mismatch",
              Map.of(
                  "currency",
                  body.getCurrency(),
                  "expected",
                  Objects.requireNonNullElse(beneficiary.currency(), "?"))));
    }
    java.math.BigDecimal amount = body.getAmount();
    if (amount != null) {
      try {
        // checked here so the channel answers 400 itself: an amount with more decimals than the
        // currency (or out of range) is never sent on, nor rounded
        Money.ofExact(amount, Objects.requireNonNull(body.getCurrency(), "currency"));
      } catch (BusinessException e) {
        return Mono.error(e);
      }
    }
    PaymentOrderRequest request =
        new PaymentOrderRequest(
            customer.id(),
            body.getDebtorAccount(),
            Objects.requireNonNull(beneficiary.cci(), "beneficiary cci"),
            body.getAmount(),
            body.getCurrency(),
            body.getDescription());
    return business
        .createPaymentOrder(idempotencyKey, request)
        .onErrorMap(e -> DownstreamErrors.translate(e, "payment", idempotencyKey))
        .map(order -> ChannelViews.payment(order, beneficiary.alias()));
  }

  private Mono<PaymentView> withAlias(Customer customer, PaymentOrder order) {
    return ReactiveComposer.within(timeout)
        .required("order", Mono.just(order))
        .optional("alias", aliasOf(customer, order), () -> null)
        .run()
        .map(
            c ->
                ChannelViews.payment(
                    Objects.requireNonNull(c.get("order", PaymentOrder.class)),
                    c.get("alias", String.class)));
  }

  /**
   * The alias of the beneficiary the order paid: orders only carry the masked account, so the match
   * is by currency and last 4 digits, and only when it is unambiguous.
   */
  private Mono<String> aliasOf(Customer customer, PaymentOrder order) {
    String creditorCci = order.creditorCci();
    if (creditorCci == null) {
      return Mono.empty();
    }
    String last = ChannelViews.lastFour(creditorCci);
    return directory
        .page(customer.id(), null, 100)
        .flatMap(
            page -> {
              List<Beneficiary> matches =
                  Objects.requireNonNullElse(page.items(), List.<Beneficiary>of()).stream()
                      .filter(
                          b -> {
                            String cci = b.cci();
                            return cci != null
                                && ChannelViews.lastFour(cci).equals(last)
                                && Objects.equals(b.currency(), order.currency());
                          })
                      .toList();
              return matches.size() == 1
                  ? Mono.justOrEmpty(matches.get(0).alias())
                  : Mono.<String>empty();
            });
  }

  private boolean currencyEnabled(Customer customer, String currency) {
    return "PEN".equals(currency)
        || ("USD".equals(currency.toUpperCase(Locale.ROOT))
            && flags.isEnabled(USD_FLAG, customer.flags()));
  }
}
