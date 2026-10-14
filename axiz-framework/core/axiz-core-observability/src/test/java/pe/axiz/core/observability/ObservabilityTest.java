package pe.axiz.core.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ObservabilityTest {

  static final class Recording implements ObservationHandler<Observation.Context> {
    final List<Observation.Context> stopped = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }

    @Override
    public void onEvent(Observation.Event event, Observation.Context context) {
      events.add(event.getName());
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }

  static final class Rejected extends RuntimeException {
    Rejected() {
      super("rejected");
    }
  }

  private final Recording handler = new Recording();
  private final ObservationRegistry registry = ObservationRegistry.create();

  {
    registry.observationConfig().observationHandler(handler);
  }

  private final ArchitectureObservation obs =
      new ArchitectureObservation(
          registry, t -> t instanceof Rejected, AttributeSanitizer.defaults());

  @Test
  void successfulObservationCarriesBusinessAttributes() {
    String result =
        obs.observe(
            AxizObservations.USE_CASE,
            "create-order",
            Map.of(AxizAttributes.CHANNEL, "web"),
            Map.of(AxizAttributes.CORRELATION_ID, "c-1", "customer.email", "ana@example.com"),
            () -> {
              obs.event("validated", null);
              return "ok";
            });
    assertEquals("ok", result);
    Observation.Context ctx = handler.stopped.get(0);
    assertEquals("axiz.usecase", ctx.getName());
    assertEquals("create-order", ctx.getContextualName());
    assertEquals("success", ctx.getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue());
    assertEquals("web", ctx.getLowCardinalityKeyValue(AxizAttributes.CHANNEL).getValue());
    assertEquals("c-1", ctx.getHighCardinalityKeyValue(AxizAttributes.CORRELATION_ID).getValue());
    assertEquals(null, ctx.getHighCardinalityKeyValue("customer.email"));
    assertEquals(List.of("validated"), handler.events);
  }

  @Test
  void startedObservationsEndOnceWhenSignalled() {
    ArchitectureObservation.Started ok =
        obs.start(AxizObservations.QUERY, "list-payments", Map.of(), Map.of());
    assertEquals(0, handler.stopped.size(), "still running");
    ok.succeed();
    ok.fail(new IllegalStateException("late")); // ignored: already finished
    assertEquals(1, handler.stopped.size());
    assertEquals(
        "success",
        handler.stopped.get(0).getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue());

    obs.start(AxizObservations.COMMAND, "pay", Map.of(), Map.of()).fail(new Rejected());
    assertEquals(
        "rejected",
        handler.stopped.get(1).getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue());
  }

  @Test
  void failuresAreClassified() {
    assertThrows(
        Rejected.class,
        () ->
            obs.run(
                AxizObservations.COMMAND,
                "pay",
                Map.of(),
                () -> {
                  throw new Rejected();
                }));
    assertEquals(
        "rejected",
        handler.stopped.get(0).getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue());
    assertTrue(handler.stopped.get(0).getError() instanceof Rejected);
    assertThrows(
        IllegalStateException.class,
        () ->
            obs.run(
                AxizObservations.COMMAND,
                "pay",
                Map.of(),
                () -> {
                  throw new IllegalStateException();
                }));
    assertEquals(
        "error",
        handler.stopped.get(1).getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue());
  }

  @Test
  void outcomeClassification() {
    assertEquals("success", Outcomes.of(null, t -> false));
    assertEquals("timeout", Outcomes.of(new RuntimeException(new TimeoutException()), t -> false));
    assertEquals("cancelled", Outcomes.of(new CancellationException(), t -> false));
    assertEquals("rejected", Outcomes.of(new Rejected(), t -> t instanceof Rejected));
    assertEquals("error", Outcomes.of(new IllegalStateException(), t -> false));
  }

  @Test
  void sanitizerRemovesPersonalData() {
    AttributeSanitizer s = new AttributeSanitizer(Set.of("customer_code"));
    Map<String, String> out =
        s.sanitize(
            Map.of(
                "note",
                "card 4111111111111111 of ana@example.com",
                "user.password",
                "x",
                "x_customer_code",
                "1"));
    assertEquals(Map.of("note", "card [redacted-number] of [redacted-email]"), out);
  }

  @Test
  void structuredEventsGoToLogAndSpan() {
    LoggingStructuredEventEmitter emitter =
        new LoggingStructuredEventEmitter(registry, AttributeSanitizer.defaults());
    Observation.createNotStarted("outer", registry)
        .observe(
            () ->
                emitter.emit(
                    new StructuredEvent(
                        StructuredEvent.Kind.BUSINESS,
                        "order.created",
                        Map.of("pan", "4111111111111111"),
                        Instant.EPOCH)));
    assertEquals(List.of("order.created"), handler.events);
    emitter.emit(
        new StructuredEvent(StructuredEvent.Kind.ERROR, "no.span", Map.of(), Instant.EPOCH));
    StructuredEventEmitter.noop()
        .emit(
            new StructuredEvent(StructuredEvent.Kind.EXTERNAL_CALL, "x", Map.of(), Instant.EPOCH));
    assertFalse(handler.events.contains("no.span"));
  }
}
