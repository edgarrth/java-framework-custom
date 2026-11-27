package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.messaging.Deduplicator;
import pe.axiz.core.messaging.EventDispatcher;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventFactory;
import pe.axiz.core.messaging.PayloadCodec;
import pe.axiz.core.messaging.PayloadException;

class MessagingWiringTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizMessagingAutoConfiguration.class));

  public record Note(String text) {}

  /** Payload cuya propiedad no puede leerse. */
  public static final class Unwritable {
    public String getValue() {
      throw new IllegalStateException("not readable");
    }
  }

  @Test
  void withoutJacksonOrInboxTheDefaultsStillWork() {
    runner
        .withPropertyValues("axiz.messaging.consumer.name=ledger")
        .run(
            ctx -> {
              PayloadCodec codec = ctx.getBean(PayloadCodec.class);
              assertInstanceOf(JacksonPayloadCodec.class, codec);
              assertEquals("application/json", codec.contentType());
              byte[] json = codec.encode(new Note("hola"));
              assertEquals(new Note("hola"), codec.decode(json, Note.class));
              assertThrows(PayloadException.class, () -> codec.encode(new Unwritable()));
              assertThrows(
                  PayloadException.class,
                  () -> codec.decode("{not json".getBytes(StandardCharsets.UTF_8), Note.class));

              EventEnvelope e =
                  ctx.getBean(EventFactory.class)
                      .create(null, "note.v1", null, new Note("x"), null);
              assertEquals("urn:axiz:application", e.source(), "no application name configured");
              assertThat(ctx).hasSingleBean(EventDispatcher.class);
            });
  }

  @Test
  void withoutAnInboxEveryDeliveryIsProcessed() {
    Deduplicator none = AxizMessagingAutoConfiguration.NoDeduplication.INSTANCE;
    Deduplicator.Claim claim = none.claim("c", "e-1");
    assertFalse(claim.token().isBlank());
    assertTrue(none.completed("c", "e-1", claim.token()));
    assertTrue(none.failed("c", "e-1", claim.token()));
  }

  @Test
  void eventsCanBeBuiltAndPublishedInTwoSteps() {
    runner
        .withBean(
            EventPublisherAdapter.class,
            () -> new EventPublisherAdapter("memory", new MessagingTest.Recorder()))
        .run(
            ctx -> {
              AxizEvents events = ctx.getBean(AxizEvents.class);
              EventEnvelope envelope = events.envelope("evt-1", "note.v1", "n-1", new Note("x"));
              assertEquals("evt-1", envelope.id());
              assertEquals("memory:notes", events.publish("memory:notes", envelope).destination());
            });
  }

  @Test
  void messagingCanBeSwitchedOff() {
    runner
        .withPropertyValues("axiz.messaging.enabled=false")
        .run(ctx -> assertNull(ctx.getBeanProvider(AxizEvents.class).getIfAvailable()));
  }
}
