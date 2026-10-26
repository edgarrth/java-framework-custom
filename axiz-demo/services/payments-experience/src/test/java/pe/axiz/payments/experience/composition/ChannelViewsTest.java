package pe.axiz.payments.experience.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.payments.experience.api.model.BeneficiaryList;
import pe.axiz.payments.experience.api.model.BeneficiaryView;
import pe.axiz.payments.experience.api.model.PaymentView;
import pe.axiz.payments.experience.client.beneficiariescrud.model.Beneficiary;
import pe.axiz.payments.experience.client.beneficiariescrud.model.BeneficiaryPage;
import pe.axiz.payments.experience.client.paymentsbusiness.model.PaymentOrder;

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

  @Test
  void missingDownstreamFieldsStayMissing() {
    PaymentView payment =
        ChannelViews.payment(
            new PaymentOrder(
                "P-1",
                "OP-1",
                "C-1",
                null,
                new BigDecimal("10.00"),
                "PEN",
                null,
                LocalDate.of(2026, 10, 12),
                null),
            null);
    BeneficiaryView beneficiary =
        ChannelViews.beneficiary(new Beneficiary("B-01", "C-1", "Rent", null, null, "PEN"));

    assertThat(payment.getStatus()).isNull();
    assertThat(payment.getCreditorCci()).isNull();
    assertThat(payment.getBeneficiaryAlias()).isNull();
    assertThat(payment.getAmount()).isEqualByComparingTo("10.00");
    assertThat(beneficiary.getHolderName()).isNull();
    assertThat(beneficiary.getCreditorCci()).isNull();
    assertThat(beneficiary.getAlias()).isEqualTo("Rent");
  }

  @Test
  void onlyDownstreamHttpFailuresAreTranslated() {
    IllegalStateException local = new IllegalStateException("bug");

    assertThat(DownstreamErrors.translate(local, "payment", "P-1")).isSameAs(local);
  }
}
