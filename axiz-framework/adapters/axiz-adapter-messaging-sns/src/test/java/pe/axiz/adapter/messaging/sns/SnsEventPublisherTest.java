package pe.axiz.adapter.messaging.sns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.core.messaging.EventEnvelope;
import pe.axiz.core.messaging.EventPublisher;
import pe.axiz.core.messaging.PublishException;
import pe.axiz.test.support.AsyncPublisherProbe;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

class SnsEventPublisherTest {

  @Test
  void publishesWithFilterAttributeAndFifoIds() {
    for (boolean nativeAsync : List.of(false, true)) {
      List<PublishRequest> sent = new ArrayList<>();
      boolean[] fail = {false};
      SnsClient sns =
          new SnsClient() {
            @Override
            public PublishResponse publish(PublishRequest r) {
              if (fail[0]) {
                throw SnsException.builder().message("x").build();
              }
              sent.add(r);
              return PublishResponse.builder().messageId("m").build();
            }

            @Override
            public String serviceName() {
              return "sns";
            }

            @Override
            public void close() {}
          };
      EventEnvelope e =
          EventEnvelope.builder()
              .id("e-1")
              .source("urn:s")
              .type("t.v1")
              .time(Instant.EPOCH)
              .subject(null)
              .dataContentType("application/json")
              .data("{}".getBytes(StandardCharsets.UTF_8))
              .extensions(Map.of())
              .build();
      EventPublisher p =
          nativeAsync
              ? new AsyncPublisherProbe(
                  new SnsAsyncEventPublisher(ImmediateAsyncFakes.adapt(SnsAsyncClient.class, sns)))
              : new SnsEventPublisher(sns);
      String fifo = "arn:aws:sns:us-east-1:123:payments.fifo";
      assertTrue(p.supports(fifo));
      assertFalse(p.supports("https://sqs.x/q"));
      p.publish(fifo, e);
      assertEquals("t.v1", sent.get(0).messageGroupId());
      assertEquals("e-1", sent.get(0).messageDeduplicationId());
      assertEquals("t.v1", sent.get(0).messageAttributes().get("ce_type").stringValue());
      assertEquals(e, CloudEventJson.read(sent.get(0).message()));
      fail[0] = true;
      assertThrows(PublishException.class, () -> p.publish(fifo, e));
    }
  }

  @Test
  void standardTopicsHaveNoGroupAndFifoGroupsBySubject() {
    for (boolean nativeAsync : List.of(false, true)) {
      List<PublishRequest> sent = new ArrayList<>();
      SnsClient sns =
          new SnsClient() {
            @Override
            public PublishResponse publish(PublishRequest r) {
              sent.add(r);
              return PublishResponse.builder().messageId("m-" + sent.size()).build();
            }

            @Override
            public String serviceName() {
              return "sns";
            }

            @Override
            public void close() {}
          };
      EventEnvelope withSubject =
          EventEnvelope.builder()
              .id("e-2")
              .source("urn:s")
              .type("t.v1")
              .time(Instant.EPOCH)
              .subject("order-7")
              .dataContentType("application/json")
              .data("{}".getBytes(StandardCharsets.UTF_8))
              .extensions(Map.of())
              .build();
      EventPublisher p =
          nativeAsync
              ? new AsyncPublisherProbe(
                  new SnsAsyncEventPublisher(ImmediateAsyncFakes.adapt(SnsAsyncClient.class, sns)))
              : new SnsEventPublisher(sns);

      String standard = "arn:aws:sns:us-east-1:123:payments";
      assertEquals("m-1", p.publish(standard, withSubject).brokerMessageId());
      assertNull(sent.get(0).messageGroupId());
      assertNull(sent.get(0).messageDeduplicationId());

      p.publish("arn:aws:sns:us-east-1:123:payments.fifo", withSubject);
      assertEquals("order-7", sent.get(1).messageGroupId(), "events of one subject stay in order");

      // an ARN of another service is not an SNS destination
      assertFalse(p.supports("arn:aws:sqs:us-east-1:123:payments"));
      assertFalse(p.supports("payments"));
    }
  }
}
