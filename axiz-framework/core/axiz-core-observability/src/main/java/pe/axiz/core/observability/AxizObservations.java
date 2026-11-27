package pe.axiz.core.observability;

/**
 * Nombres de las observaciones (spans y timers) que crea el framework en los puntos de la
 * arquitectura.
 */
public final class AxizObservations {

  public static final String USE_CASE = "axiz.usecase";
  public static final String COMMAND = "axiz.command";
  public static final String QUERY = "axiz.query";
  public static final String EVENT_HANDLER = "axiz.event.handler";
  public static final String EVENT_PUBLISH = "axiz.event.publish";
  public static final String OUTBOX_RELAY = "axiz.outbox.relay";

  /** Escritura de un evento en el outbox: su span es el padre de la publicación posterior. */
  public static final String OUTBOX_WRITE = "axiz.outbox.write";

  /** Registro del outbox tomado por el relay, que continúa la traza de su escritura. */
  public static final String OUTBOX_DISPATCH = "axiz.outbox.dispatch";

  public static final String INBOX = "axiz.inbox";
  public static final String SAGA_STEP = "axiz.saga.step";
  public static final String BATCH_STEP = "axiz.batch.step";
  public static final String CLIENT = "axiz.client";
  public static final String SOAP_CLIENT = "axiz.soap.client";
  public static final String AWS_SDK = "axiz.aws.sdk";

  private AxizObservations() {}
}
