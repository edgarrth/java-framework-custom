package pe.axiz.core.logging;

/**
 * Nombres estándar de campos de log estructurado (claves de MDC). {@code trace_id} y {@code
 * span_id} los completa Micrometer Tracing; el resto, los filtros y listeners del framework.
 */
public final class LogFields {

  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String CORRELATION_ID = "correlationId";
  public static final String REQUEST_ID = "requestId";
  public static final String SERVICE = "service";
  public static final String OPERATION = "operation";
  public static final String CHANNEL = "channel";
  public static final String CLIENT_ID = "clientId";
  public static final String TENANT = "tenant";
  public static final String OUTCOME = "outcome";
  public static final String DURATION_MS = "durationMs";
  public static final String ERROR_CODE = "errorCode";

  private LogFields() {}
}
