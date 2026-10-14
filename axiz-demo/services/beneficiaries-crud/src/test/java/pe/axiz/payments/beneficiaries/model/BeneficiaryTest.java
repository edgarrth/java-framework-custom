package pe.axiz.payments.beneficiaries.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BeneficiaryTest {

  static final Instant T0 = Instant.parse("2026-10-12T15:00:00Z");

  Beneficiary created() {
    return Beneficiary.register(
        "C-1", "B-1", new NewBeneficiary("Rent ", " Ana", "00219100123456789053", "PEN"), T0);
  }

  @Test
  void registersActiveAndTrimmed() {
    Beneficiary b = created();
    assertThat(b.removed()).isFalse();
    assertThat(b.getAlias()).isEqualTo("Rent");
    assertThat(b.getHolderName()).isEqualTo("Ana");
    assertThat(b.getVersion()).isNull();
    assertThat(b.sameAccount("00219100123456789053")).isTrue();
  }

  @Test
  void renameAndRemoveTrackTheChangeTime() {
    Beneficiary b = created();
    Instant later = T0.plusSeconds(60);

    b.rename("Home", later);
    assertThat(b.getAlias()).isEqualTo("Home");
    assertThat(b.getUpdatedAt()).isEqualTo(later);

    b.remove(later.plusSeconds(1));
    assertThat(b.removed()).isTrue();
    assertThat(b.getDeletedAt()).isEqualTo(later.plusSeconds(1));
  }

  @Test
  void toStringDoesNotExposeAccountData() {
    assertThat(created().toString()).doesNotContain("0021910").doesNotContain("Ana");
  }

  @Test
  void policyNeedsAPositiveMaximum() {
    assertThatThrownBy(() -> new BeneficiaryPolicy(0)).isInstanceOf(IllegalArgumentException.class);
  }
}
