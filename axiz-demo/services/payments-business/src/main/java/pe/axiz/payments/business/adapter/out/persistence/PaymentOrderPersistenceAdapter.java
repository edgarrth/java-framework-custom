package pe.axiz.payments.business.adapter.out.persistence;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;
import pe.axiz.payments.business.application.port.out.PaymentOrders;
import pe.axiz.payments.business.domain.PaymentErrors;
import pe.axiz.payments.business.domain.PaymentOrder;
import pe.axiz.payments.business.domain.PaymentStatus;

/** {@link PaymentOrders} on Aurora PostgreSQL (JPA). */
@Component
public class PaymentOrderPersistenceAdapter implements PaymentOrders {

  static final String CCI_CONTEXT = "payment-order.creditor-cci";

  /** Unique index of (customer_id, request_key), see V3__payment_limits_and_request_keys.sql. */
  static final String REQUEST_KEY_CONSTRAINT = "payment_order_request_key";

  private final PaymentOrderJpaRepository repository;
  private final BlindIndex blindIndex;

  public PaymentOrderPersistenceAdapter(
      PaymentOrderJpaRepository repository, BlindIndex blindIndex) {
    this.repository = repository;
    this.blindIndex = blindIndex;
  }

  @Override
  public void create(PaymentOrder order, @Nullable String requestKey) {
    PaymentOrderEntity entity = newEntity(order);
    entity.requestKey(requestKey);
    entity.status(order.status().name(), order.statusReason());
    try {
      repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      // the transaction is aborted now: classify by constraint, the client retries and gets
      // the order the other request stored
      String cause = String.valueOf(e.getMostSpecificCause().getMessage());
      if (requestKey == null || !cause.contains(REQUEST_KEY_CONSTRAINT)) {
        throw e;
      }
      throw new BusinessException(
          PaymentErrors.CONCURRENT_REQUEST,
          "request key stored first by another request",
          Map.of());
    }
  }

  @Override
  public Optional<PaymentOrder> findByRequestKey(String customerId, String requestKey) {
    return repository
        .findByCustomerIdAndRequestKey(customerId, requestKey)
        .map(PaymentOrderPersistenceAdapter::toDomain);
  }

  @Override
  public void save(PaymentOrder order) {
    UUID id = UUID.fromString(order.id());
    PaymentOrderEntity entity = repository.findById(id).orElseGet(() -> newEntity(order));
    entity.status(order.status().name(), order.statusReason());
    repository.save(entity);
  }

  private PaymentOrderEntity newEntity(PaymentOrder order) {
    return new PaymentOrderEntity(
        UUID.fromString(order.id()),
        order.operationNumber(),
        order.customerId(),
        order.debtorAccount(),
        order.creditorCci(),
        blindIndex.index(order.creditorCci(), CCI_CONTEXT),
        order.amount().amount(),
        order.amount().currencyCode(),
        order.description(),
        order.processingDate());
  }

  @Override
  public Optional<PaymentOrder> find(String id) {
    try {
      return repository.findById(UUID.fromString(id)).map(PaymentOrderPersistenceAdapter::toDomain);
    } catch (IllegalArgumentException notAUuid) {
      return Optional.empty();
    }
  }

  @Override
  public Page<PaymentOrder> byCustomer(String customerId, PageRequest page) {
    var result =
        repository.findByCustomerId(
            customerId,
            org.springframework.data.domain.PageRequest.of(
                page.page(), page.size(), Sort.by(Sort.Order.desc("createdAt"))));
    return Page.of(
        result.getContent().stream().map(PaymentOrderPersistenceAdapter::toDomain).toList(),
        page,
        result.getTotalElements());
  }

  @Override
  public boolean reserveDaily(
      String customerId, Money amount, LocalDate processingDate, Money limit) {
    return repository.reserve(
            customerId, amount.currencyCode(), processingDate, amount.amount(), limit.amount())
        == 1;
  }

  @Override
  public void releaseDaily(String customerId, Money amount, LocalDate processingDate) {
    repository.release(customerId, amount.currencyCode(), processingDate, amount.amount());
  }

  @Override
  public Money reservedOn(String customerId, String currency, LocalDate processingDate) {
    return Money.of(repository.reserved(customerId, currency, processingDate), currency);
  }

  @Override
  public boolean exists(
      String customerId, String creditorCci, Money amount, LocalDate processingDate) {
    return repository.existsByCustomerIdAndCreditorCciIndexAndAmountAndProcessingDateAndStatusNot(
        customerId,
        blindIndex.index(creditorCci, CCI_CONTEXT),
        amount.amount(),
        processingDate,
        PaymentStatus.REJECTED.name());
  }

  static PaymentOrder toDomain(PaymentOrderEntity e) {
    return new PaymentOrder(
        e.getId().toString(),
        e.getOperationNumber(),
        e.getCustomerId(),
        e.getDebtorAccount(),
        e.getCreditorCci(),
        Money.of(e.getAmount(), e.getCurrency()),
        e.getDescription(),
        e.getProcessingDate(),
        java.util.Objects.requireNonNullElse(e.getCreatedAt(), java.time.Instant.EPOCH),
        PaymentStatus.valueOf(e.getStatus()),
        e.getStatusReason());
  }
}
