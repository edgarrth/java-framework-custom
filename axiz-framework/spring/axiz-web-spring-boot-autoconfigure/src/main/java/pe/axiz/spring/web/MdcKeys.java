package pe.axiz.spring.web;

/** Claves MDC escritas por la capa web; el starter de logging las emite como campos JSON. */
public final class MdcKeys {

  public static final String CORRELATION_ID = "correlationId";
  public static final String REQUEST_ID = "requestId";

  private MdcKeys() {}
}
