package pe.axiz.core.soap;

/** Versiones del protocolo SOAP. */
public enum SoapVersion {
  SOAP_11("http://schemas.xmlsoap.org/soap/envelope/", "text/xml; charset=utf-8"),
  SOAP_12("http://www.w3.org/2003/05/soap-envelope", "application/soap+xml; charset=utf-8");

  private final String namespace;
  private final String contentType;

  SoapVersion(String namespace, String contentType) {
    this.namespace = namespace;
    this.contentType = contentType;
  }

  public String namespace() {
    return namespace;
  }

  public String contentType() {
    return contentType;
  }
}
