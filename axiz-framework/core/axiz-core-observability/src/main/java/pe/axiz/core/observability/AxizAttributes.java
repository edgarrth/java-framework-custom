package pe.axiz.core.observability;

/**
 * Standard attribute keys. Low cardinality keys become metric tags and span attributes; high
 * cardinality keys only span attributes.
 */
public final class AxizAttributes {

  // low cardinality
  public static final String OPERATION = "axiz.operation";
  public static final String COMPONENT = "axiz.component";
  public static final String CHANNEL = "axiz.channel";
  public static final String OUTCOME = "axiz.outcome";
  public static final String ERROR_CODE = "axiz.error.code";
  public static final String ERROR_TYPE = "axiz.error.type";
  public static final String CLIENT = "axiz.client";
  public static final String EVENT_TYPE = "axiz.event.type";
  public static final String DESTINATION = "axiz.destination";
  public static final String AWS_SERVICE = "aws.service";
  public static final String AWS_OPERATION = "aws.operation";

  // high cardinality
  public static final String CORRELATION_ID = "axiz.correlation_id";
  public static final String EVENT_ID = "axiz.event.id";
  public static final String AGGREGATE_ID = "axiz.aggregate.id";
  public static final String TENANT = "axiz.tenant";
  public static final String AWS_REQUEST_ID = "aws.request_id";

  private AxizAttributes() {}
}
