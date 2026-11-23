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
              // un handler de servicio: sus mensajes pueden repetir datos de negocio y nunca se
              // registran en log
              var serviceFrame =
                  new StackTraceElement[] {
                    new StackTraceElement("com.example.payments.Handler", "handle", "H.java", 7)
                  };
              var cause = new IllegalArgumentException(marker);
              cause.setStackTrace(serviceFrame);
              var failure = new IllegalStateException(marker, cause);
              failure.setStackTrace(serviceFrame);
              throw failure;
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
      assertTrue(
          capture.list.stream()
              .anyMatch(
                  e -> e.getFormattedMessage().contains("at com.example.payments.Handler.handle")),
          "the origin frame locates the failure");
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
