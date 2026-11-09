package pe.axiz.payments.orders.adapter.out.persistence;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.crypto.BlindIndex;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.money.Money;
import pe.axiz.payments.orders.application.port.out.PaymentOrders;
import pe.axiz.payments.orders.domain.PaymentErrors;
import pe.axiz.payments.orders.domain.PaymentOrder;
import pe.axiz.payments.orders.domain.PaymentStatus;

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
      if (requestKey == null || !duplicateRequestKey(e)) {
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
    PaymentOrderEntity entity =
        repository
            .findById(id)
            .orElseThrow(
                () ->
                    new DataRetrievalFailureException("Cannot update missing payment order " + id));
    entity.status(order.status().name(), order.statusReason());
    repository.save(entity);
  }

  private static boolean duplicateRequestKey(Throwable failure) {
    var pending = new ArrayDeque<Throwable>();
    var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.remove();
      if (!visited.add(current)) continue;
      if (current instanceof PSQLException postgres && "23505".equals(postgres.getSQLState())) {
        var server = postgres.getServerErrorMessage();
        if (server != null && REQUEST_KEY_CONSTRAINT.equals(server.getConstraint())) return true;
      }
      Throwable cause = current.getCause();
      if (cause != null) pending.add(cause);
      if (current instanceof SQLException sql) {
        SQLException next = sql.getNextException();
        if (next != null) pending.add(next);
      }
    }
    return false;
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
    } catch (IllegalArgumentException _) {
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
    return Money.ofExact(repository.reserved(customerId, currency, processingDate), currency);
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
        Money.ofExact(e.getAmount(), e.getCurrency()),
        e.getDescription(),
        e.getProcessingDate(),
        Objects.requireNonNullElse(e.getCreatedAt(), Instant.EPOCH),
        PaymentStatus.valueOf(e.getStatus()),
        e.getStatusReason());
  }
}
