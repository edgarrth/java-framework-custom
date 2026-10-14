package pe.axiz.payments.coreintegration.adapter.out.soap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import pe.axiz.core.money.Money;
import pe.axiz.core.soap.SafeXml;
import pe.axiz.payments.coreintegration.domain.MovementOrder;

class CoreCreditGatewayTest {

  @Test
  void requestFollowsTheWsdlAndEscapesValues() {
    String xml =
        CoreCreditGateway.request(
            new MovementOrder("1910012345678", Money.of("10.50", "PEN"), "OP<1>&2"));

    assertThat(xml).contains("urn:core:credit:v1").contains("CreditRequest");
    assertThat(xml).contains("<cc:amount>10.50</cc:amount>");
    assertThat(xml).contains("OP&lt;1&gt;&amp;2").doesNotContain("OP<1>");
  }

  @Test
  void readsTheResponseRegardlessOfPrefixes() {
    Element response =
        SafeXml.parse(
                "<r:CreditResponse xmlns:r=\"urn:core:credit:v1\">"
                    + "<r:movementId> MV-9 </r:movementId><r:status>POSTED</r:status>"
                    + "</r:CreditResponse>")
            .getDocumentElement();

    assertThat(CoreCreditGateway.text(response, "movementId")).isEqualTo("MV-9");
    assertThat(CoreCreditGateway.text(response, "missing")).isNull();
  }
}
