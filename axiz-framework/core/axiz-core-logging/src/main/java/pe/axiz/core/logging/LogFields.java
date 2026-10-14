package pe.axiz.core.logging;

/**
 * Standard structured-log field names (MDC keys). {@code trace_id} and {@code span_id} are filled
 * by Micrometer Tracing; the rest by the framework filters and listeners.
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
