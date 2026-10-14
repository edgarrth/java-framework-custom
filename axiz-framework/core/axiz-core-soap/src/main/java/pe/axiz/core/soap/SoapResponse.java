package pe.axiz.core.soap;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pe.axiz.core.errors.ExternalErrorDetail;
import pe.axiz.core.errors.ExternalServiceException;

/** Parses SOAP responses: extracts the body payload or turns a Fault into an external error. */
public final class SoapResponse {

  private SoapResponse() {}

  /**
   * Returns the first element inside the Body.
   *
   * @param system logical name of the dependency
   * @param httpStatus HTTP status of the response
   * @param xml response body
   * @return the payload element
   */
  public static Element payload(String system, int httpStatus, String xml) {
    if (xml == null || xml.isBlank()) {
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, httpStatus, null, "empty SOAP response", ""));
    }
    Document doc = SafeXml.parse(xml);
    Element body = firstChild(doc.getDocumentElement(), "Body");
    if (body == null) {
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, httpStatus, null, "no SOAP Body", xml));
    }
    Element first = firstElement(body);
    if (first == null) {
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, httpStatus, null, "empty SOAP Body", xml));
    }
    if ("Fault".equals(first.getLocalName())) {
      String code = text(first, "faultcode", "Value");
      String reason = text(first, "faultstring", "Text");
      int status = httpStatus == 200 ? 500 : httpStatus;
      throw ExternalServiceException.from(
          new ExternalErrorDetail(system, status, code, reason, xml));
    }
    return first;
  }

  private static @Nullable Element firstChild(Element parent, String localName) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n instanceof Element e && localName.equals(e.getLocalName())) {
        return e;
      }
    }
    return null;
  }

  private static @Nullable Element firstElement(Element parent) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element e) {
        return e;
      }
    }
    return null;
  }

  private static @Nullable String text(Element fault, String soap11, String soap12) {
    NodeList a = fault.getElementsByTagNameNS("*", soap11);
    if (a.getLength() == 0) {
      a = fault.getElementsByTagName(soap11);
    }
    if (a.getLength() > 0) {
      return a.item(0).getTextContent().strip();
    }
    NodeList b = fault.getElementsByTagNameNS("*", soap12);
    return b.getLength() > 0 ? b.item(0).getTextContent().strip() : null;
  }
}
