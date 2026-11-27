package pe.axiz.payments.bankingcore.adapter.out.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import pe.axiz.core.money.Money;
import pe.axiz.core.soap.SafeXml;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.payments.bankingcore.domain.MovementStatus;
import pe.axiz.spring.soap.AxizSoapProperties;
import pe.axiz.spring.soap.SoapClients;

class CoreCreditGatewayTest {

  /** Clientes SOAP que responden un payload fijo y registran lo que se invocó. */
  static final class Answering extends SoapClients {
    final List<String> calls = new ArrayList<>();
    String response = "";

    Answering() {
      super(
          new AxizSoapProperties(Map.of()),
          null,
          ObservationRegistry.NOOP,
          Clock.systemUTC(),
          TransportSecurityPolicy.secure());
    }

    @Override
    public Element call(String client, String soapAction, String payloadXml) {
      calls.add(client + " " + soapAction + " " + payloadXml);
      return SafeXml.parse(response).getDocumentElement();
    }
  }

  static String response(String body) {
    return "<r:CreditResponse xmlns:r=\"urn:core:credit:v1\">" + body + "</r:CreditResponse>";
  }

  final MovementOrder order = new MovementOrder("1910012345678", Money.of("10.50", "PEN"), "OP-1");

  @Test
  void creditCallsTheCoreCreditOperationAndReadsTheMovement() {
    Answering soap = new Answering();
    CoreCreditGateway gateway = new CoreCreditGateway(soap);

    soap.response = response("<r:movementId>MV-9</r:movementId><r:status>POSTED</r:status>");
    assertThat(gateway.credit(order))
        .isEqualTo(new Movement("MV-9", MovementStatus.POSTED, "OP-1"));
    assertThat(soap.calls).hasSize(1);
    assertThat(soap.calls.getFirst())
        .startsWith("core-credit urn:core:credit:v1:Credit ")
        .contains("<cc:account>1910012345678</cc:account>");

    soap.response = response("<r:movementId>MV-8</r:movementId><r:status>REVERSED</r:status>");
    assertThat(gateway.credit(order).status()).isEqualTo(MovementStatus.REVERSED);

    soap.response = response("<r:movementId>MV-7</r:movementId>");
    assertThat(gateway.credit(order).status())
        .as("without a status the credit was posted")
        .isEqualTo(MovementStatus.POSTED);
  }

  @Test
  void aResponseWithoutMovementIsAnError() {
    Answering soap = new Answering();
    soap.response = response("<r:status>POSTED</r:status>");

    assertThatThrownBy(() -> new CoreCreditGateway(soap).credit(order))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("movementId");
  }

  @Test
  void elementsWithoutNamespaceAreReadToo() {
    Element plain =
        SafeXml.parse("<CreditResponse><movementId>MV-1</movementId></CreditResponse>")
            .getDocumentElement();

    assertThat(CoreCreditGateway.text(plain, "movementId")).isEqualTo("MV-1");
  }

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
