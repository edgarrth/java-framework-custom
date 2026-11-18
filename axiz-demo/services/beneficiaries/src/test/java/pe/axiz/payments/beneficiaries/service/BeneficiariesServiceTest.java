package pe.axiz.payments.beneficiaries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import pe.axiz.core.api.Page;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.NotFoundException;
import pe.axiz.payments.beneficiaries.model.Beneficiary;
import pe.axiz.payments.beneficiaries.model.BeneficiaryErrors;
import pe.axiz.payments.beneficiaries.model.NewBeneficiary;

class BeneficiariesServiceTest {

  static final String VALID_CCI = "00219100123456789053";
  static final String OTHER_CCI = "00319300987654321003";

  final Clock clock = Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC);
  final InMemoryBeneficiaries repository = new InMemoryBeneficiaries();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final BeneficiariesService service = Services.beneficiaries(repository, clock, meters, 2);

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
