package pe.axiz.payments.bankingcore.adapter.out.soap;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pe.axiz.core.soap.SafeXml;
import pe.axiz.payments.bankingcore.domain.Movement;
import pe.axiz.payments.bankingcore.domain.MovementOrder;
import pe.axiz.payments.bankingcore.domain.MovementStatus;
import pe.axiz.spring.soap.SoapClients;

/**
 * Servicio de abonos del core (SOAP 1.1, contrato en contracts/external/core-credit.wsdl.xml),
 * mediante el cliente SOAP de axiz {@code core-credit}: WS-Security, timeouts, logs enmascarados y
 * faults convertidos en errores de dependencia.
 */
@Component
public class CoreCreditGateway {

  static final String CLIENT = "core-credit";
  static final String NS = "urn:core:credit:v1";
  static final String ACTION = NS + ":Credit";

  private final SoapClients soap;

  public CoreCreditGateway(SoapClients soap) {
    this.soap = soap;
  }

  public Movement credit(MovementOrder order) {
    Element response = soap.call(CLIENT, ACTION, request(order));
    String id = Objects.requireNonNull(text(response, "movementId"), "credit without movementId");
    String status = text(response, "status");
    return new Movement(
        id,
        "REVERSED".equals(status) ? MovementStatus.REVERSED : MovementStatus.POSTED,
        order.reference());
  }

  /** Construye el payload con DOM, de modo que los valores siempre se escapan. */
  static String request(MovementOrder order) {
    Document doc = SafeXml.newDocument();
    Element root = doc.createElementNS(NS, "cc:CreditRequest");
    doc.appendChild(root);
    add(doc, root, "account", order.accountId());
    add(doc, root, "amount", order.amount().amount().toPlainString());
    add(doc, root, "currency", order.amount().currencyCode());
    add(doc, root, "reference", order.reference());
    return SafeXml.toString(root);
  }

  private static void add(Document doc, Element parent, String name, String value) {
    Element e = doc.createElementNS(NS, "cc:" + name);
    e.setTextContent(value);
    parent.appendChild(e);
  }

  static @Nullable String text(Element parent, String localName) {
    NodeList nodes = parent.getElementsByTagNameNS("*", localName);
    if (nodes.getLength() == 0) {
      nodes = parent.getElementsByTagName(localName);
    }
    Node n = nodes.item(0);
    return n == null ? null : n.getTextContent().strip();
  }
}
