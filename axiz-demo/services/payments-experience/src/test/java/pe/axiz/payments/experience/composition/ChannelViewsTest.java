package pe.axiz.payments.experience.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.experience.api.model.BeneficiaryList;
import pe.axiz.payments.experience.client.beneficiariescrud.model.Beneficiary;
import pe.axiz.payments.experience.client.beneficiariescrud.model.BeneficiaryPage;

class ChannelViewsTest {

  @Test
  void masksHolderNamesAndAccounts() {
    BeneficiaryList list =
        ChannelViews.beneficiaries(
            new BeneficiaryPage(
                List.of(new Beneficiary("B-01", "C-1", "Rent", "Ana Torres", Fakes.CCI, "PEN")),
                "next"));

    assertThat(list.getItems()).hasSize(1);
    assertThat(list.getItems().get(0).getHolderName()).isEqualTo("A** T*****");
    assertThat(list.getItems().get(0).getCreditorCci()).isEqualTo("****************9053");
    assertThat(list.getNextCursor()).isEqualTo("next");
  }

  @Test
  void emptyPagesAndAlreadyMaskedValuesAreSafe() {
    assertThat(ChannelViews.beneficiaries(new BeneficiaryPage(null, null)).getItems()).isEmpty();
    assertThat(ChannelViews.account("****************9053")).isEqualTo("****************9053");
    assertThat(ChannelViews.lastFour("123")).isEqualTo("123");
  }
}
