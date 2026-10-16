package pe.axiz.core.observability;

/** Names of the observations (spans and timers) created by the framework at architecture points. */
public final class AxizObservations {

  public static final String USE_CASE = "axiz.usecase";
  public static final String COMMAND = "axiz.command";
  public static final String QUERY = "axiz.query";
  public static final String EVENT_HANDLER = "axiz.event.handler";
  public static final String EVENT_PUBLISH = "axiz.event.publish";
  public static final String OUTBOX_RELAY = "axiz.outbox.relay";

  /** Writing an event to the outbox: its span is the parent of the later publication. */
  public static final String OUTBOX_WRITE = "axiz.outbox.write";

  /** One outbox record taken by the relay, continuing the trace of its write. */
  public static final String OUTBOX_DISPATCH = "axiz.outbox.dispatch";

  public static final String INBOX = "axiz.inbox";
  public static final String SAGA_STEP = "axiz.saga.step";
  public static final String BATCH_STEP = "axiz.batch.step";
  public static final String CLIENT = "axiz.client";
  public static final String SOAP_CLIENT = "axiz.soap.client";
  public static final String AWS_SDK = "axiz.aws.sdk";

  private AxizObservations() {}
}
