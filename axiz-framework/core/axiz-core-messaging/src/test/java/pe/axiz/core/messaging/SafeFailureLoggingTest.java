package pe.axiz.core.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SafeFailureLoggingTest {
  @Test
  void handlerFailuresExposeNeitherMessageCauseNorSensitiveValue() {
    String marker = "DOC-SYNTHETIC-ONLY-001";
    var logger = (Logger) LoggerFactory.getLogger(EventDispatcher.class);
    var capture = new ListAppender<ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var handler =
          new EventHandler<String>() {
            public String eventType() {
              return "synthetic.event";
            }

            public Class<String> payloadType() {
              return String.class;
            }

            public void handle(String value, EventEnvelope event) {
              throw new IllegalStateException(marker, new IllegalArgumentException(marker));
            }
          };
      var codec =
          new PayloadCodec() {
            public String contentType() {
              return "application/json";
            }

            public byte[] encode(Object data) {
              return new byte[0];
            }

            public <T> T decode(byte[] data, Class<T> type) {
              return type.cast("synthetic");
            }
          };
      var dispatcher =
          new EventDispatcher(
              "probe",
              List.of(handler),
              codec,
              Deduplicator.none(),
              ConsumerRetryPolicy.defaults(),
              false);
      var event =
          EventEnvelope.builder()
              .id("synthetic-id")
              .source("urn:probe")
              .type("synthetic.event")
              .time(Instant.parse("2026-01-01T00:00:00Z"))
              .dataContentType("application/json")
              .data(new byte[0])
              .build();
      dispatcher.dispatch(event, 1);
      assertFalse(capture.list.isEmpty());
      assertTrue(
          capture.list.stream()
              .anyMatch(e -> e.getFormattedMessage().contains("IllegalStateException")));
      for (var logged : capture.list) {
        assertFalse(logged.getFormattedMessage().contains(marker));
        assertNull(logged.getThrowableProxy());
      }
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }
}
