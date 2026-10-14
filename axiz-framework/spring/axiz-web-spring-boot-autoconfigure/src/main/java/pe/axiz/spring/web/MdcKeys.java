package pe.axiz.spring.web;

/** MDC keys written by the web layer; the logging starter emits them as JSON fields. */
public final class MdcKeys {

  public static final String CORRELATION_ID = "correlationId";
  public static final String REQUEST_ID = "requestId";

  private MdcKeys() {}
}
