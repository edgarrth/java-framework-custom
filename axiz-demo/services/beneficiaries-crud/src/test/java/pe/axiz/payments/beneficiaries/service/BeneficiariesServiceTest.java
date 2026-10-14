package pe.axiz.payments.beneficiaries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pe.axiz.core.cache.AxizCache;
import pe.axiz.core.cache.InMemoryByteCache;
import pe.axiz.core.cache.ValueCodec;
import pe.axiz.core.commons.Page;
import pe.axiz.core.commons.PageMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.core.identifiers.IdentifierRegistry;
import pe.axiz.core.metrics.EventMetrics;
import pe.axiz.core.metrics.MetricsSettings;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.payments.beneficiaries.model.BeneficiaryPolicy;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;
import pe.axiz.payments.beneficiaries.repository.BeneficiaryRepository;

class BeneficiariesServiceTest {

  static final String VALID_CCI = "00219100123456789053";
  static final String OTHER_CCI = "00319300987654321003";

  /** In-memory repository with the same semantics (active only, insertion order). */
  static final class InMemoryRepository implements BeneficiaryRepository {
    final Map<String, Beneficiary> rows = new LinkedHashMap<>();
    int reads;

    @Override
    public Optional<Beneficiary> findActive(String customerId, String beneficiaryId) {
      reads++;
      return Optional.ofNullable(rows.get(customerId + "/" + beneficiaryId))
          .filter(b -> !b.removed());
    }

    @Override
    public Page<Beneficiary> listActive(String customerId, int size, @Nullable String cursor) {
      List<Beneficiary> all = allActive(customerId);
      int from = cursor == null ? 0 : Integer.parseInt(cursor);
      int to = Math.min(all.size(), from + size);
      return new Page<>(
          all.subList(from, to),
          new PageMetadata(0, size, -1, -1),
          to < all.size() ? Integer.toString(to) : null);
    }

    @Override
    public List<Beneficiary> allActive(String customerId) {
      return rows.values().stream()
          .filter(b -> b.getCustomerId().equals(customerId) && !b.removed())
          .toList();
    }

    @Override
    public void insert(Beneficiary b) {
      if (rows.putIfAbsent(b.getCustomerId() + "/" + b.getBeneficiaryId(), b) != null) {
        throw new IllegalStateException("exists");
      }
    }

    @Override
    public Beneficiary update(Beneficiary b) {
      rows.put(b.getCustomerId() + "/" + b.getBeneficiaryId(), b);
      return b;
    }
  }

  /** Keeps values by reference: enough to test the cache flow without a JSON codec. */
  static final class ReferenceCodec implements ValueCodec {
    final Map<String, Object> values = new ConcurrentHashMap<>();

    @Override
    public byte[] encode(Object value) {
      String id = UUID.randomUUID().toString();
      values.put(id, value);
      return id.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <T> T decode(byte[] bytes, Class<T> type) {
      return type.cast(values.get(new String(bytes, StandardCharsets.UTF_8)));
    }
  }

  final Clock clock = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
  final InMemoryRepository repository = new InMemoryRepository();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final AtomicInteger ids = new AtomicInteger();
  final BeneficiariesService service =
      new BeneficiariesService(
          repository,
          new AxizCache(
              "test",
              "beneficiaries",
              new InMemoryByteCache(100, clock),
              new ReferenceCodec(),
              Duration.ofMinutes(5)),
          IdentifierRegistry.withDiscoveredTypes(),
          () -> "B-" + ids.incrementAndGet(),
          new EventMetrics(meters, MetricsSettings.defaults()),
          clock,
          new BeneficiaryPolicy(2));

  static NewBeneficiary beneficiary(String cci) {
    return new NewBeneficiary(" Landlord ", "Ana Torres", cci, "PEN");
  }

  @Test
  void registersAValidBeneficiary() {
    Beneficiary created = service.create("C-1", beneficiary(VALID_CCI));

    assertThat(created.getBeneficiaryId()).isEqualTo("B-1");
    assertThat(created.getAlias()).isEqualTo("Landlord");
    assertThat(created.getCreatedAt()).isEqualTo(clock.instant());
    assertThat(repository.allActive("C-1")).containsExactly(created);
  }

  @Test
  void rejectsAccountsWithWrongCheckDigits() {
    assertThatThrownBy(() -> service.create("C-1", beneficiary("00219100123456789099")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(BeneficiaryErrors.INVALID_ACCOUNT));
    assertThat(repository.rows).isEmpty();
  }

  @Test
  void rejectsTheSameAccountTwiceButAllowsItAfterRemoval() {
    Beneficiary first = service.create("C-1", beneficiary(VALID_CCI));

    assertThatThrownBy(() -> service.create("C-1", beneficiary(VALID_CCI)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(BeneficiaryErrors.DUPLICATE_ACCOUNT));

    service.remove("C-1", first.getBeneficiaryId());
    assertThat(service.create("C-1", beneficiary(VALID_CCI)).getBeneficiaryId()).isEqualTo("B-2");
    assertThat(service.create("C-2", beneficiary(VALID_CCI)).getCustomerId()).isEqualTo("C-2");
  }

  @Test
  void enforcesTheLimitPerCustomer() {
    service.create("C-1", beneficiary(VALID_CCI));
    service.create("C-1", beneficiary(OTHER_CCI));

    assertThatThrownBy(() -> service.create("C-1", beneficiary("00219100123456780052")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.definition()).isEqualTo(BeneficiaryErrors.LIMIT_REACHED));
  }

  @Test
  void removedBeneficiariesAreKeptButNotReturned() {
    Beneficiary created = service.create("C-1", beneficiary(VALID_CCI));

    service.remove("C-1", created.getBeneficiaryId());

    assertThat(repository.rows).hasSize(1);
    assertThat(created.getDeletedAt()).isEqualTo(clock.instant());
    assertThatThrownBy(() -> service.get("C-1", created.getBeneficiaryId()))
        .isInstanceOf(NotFoundException.class);
    assertThat(service.list("C-1", 10, null).content()).isEmpty();
  }

  @Test
  void readsAreCachedAndChangesEvictTheEntry() {
    Beneficiary created = service.create("C-1", beneficiary(VALID_CCI));

    service.get("C-1", created.getBeneficiaryId());
    service.get("C-1", created.getBeneficiaryId());
    assertThat(repository.reads).isEqualTo(1);

    service.rename("C-1", created.getBeneficiaryId(), "Home rent");
    assertThat(service.get("C-1", created.getBeneficiaryId()).getAlias()).isEqualTo("Home rent");
    assertThat(repository.reads).isEqualTo(3);
  }

  @Test
  void listsWithCursorPagination() {
    service.create("C-1", beneficiary(VALID_CCI));
    service.create("C-1", beneficiary(OTHER_CCI));

    Page<Beneficiary> first = service.list("C-1", 1, null);
    Page<Beneficiary> second = service.list("C-1", 1, first.nextCursor());

    assertThat(first.content()).extracting(Beneficiary::getCci).containsExactly(VALID_CCI);
    assertThat(second.content()).extracting(Beneficiary::getCci).containsExactly(OTHER_CCI);
    assertThat(second.nextCursor()).isNull();
  }

  @Test
  void unknownBeneficiariesAreNotFound() {
    assertThatThrownBy(() -> service.rename("C-1", "B-404", "x"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.remove("C-1", "B-404")).isInstanceOf(NotFoundException.class);
  }
}
