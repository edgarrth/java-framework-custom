package pe.axiz.core.observability;

/**
 * Claves de atributo estándar. Las claves de baja cardinalidad se convierten en tags de métricas y
 * atributos de span; las de alta cardinalidad, solo en atributos de span.
 */
public final class AxizAttributes {

  // baja cardinalidad
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

  // alta cardinalidad
  public static final String CORRELATION_ID = "axiz.correlation_id";
  public static final String EVENT_ID = "axiz.event.id";
  public static final String AGGREGATE_ID = "axiz.aggregate.id";
  public static final String TENANT = "axiz.tenant";
  public static final String AWS_REQUEST_ID = "aws.request_id";

  private AxizAttributes() {}
}
