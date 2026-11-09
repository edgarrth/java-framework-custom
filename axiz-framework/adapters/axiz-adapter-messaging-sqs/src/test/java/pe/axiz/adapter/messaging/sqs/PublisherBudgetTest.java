package pe.axiz.adapter.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pe.axiz.core.messaging.BoundedPublisher;
import pe.axiz.core.messaging.PublishBound;
import pe.axiz.test.support.ImmediateAsyncFakes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsServiceClientConfiguration;

class PublisherBudgetTest {
  enum Configuration {
    KNOWN,
    MISSING_TIMEOUT,
    NULL_CONFIGURATION,
    UNSUPPORTED
  }

  @ParameterizedTest
  @EnumSource(Configuration.class)
  void bothStacksRespectTheEffectiveSdkTimeoutAndKeepUnknownClientsUnknown(Configuration mode) {
    Duration timeout = Duration.ofSeconds(37);
    SqsClient client =
        new SqsClient() {
          @Override
          public SqsServiceClientConfiguration serviceClientConfiguration() {
            if (mode == Configuration.NULL_CONFIGURATION) return null;
            if (mode == Configuration.UNSUPPORTED)
              throw new UnsupportedOperationException(
                  "custom client has no configuration snapshot");
            var overrides = ClientOverrideConfiguration.builder();
            if (mode == Configuration.KNOWN) overrides.apiCallTimeout(timeout);
            return SqsServiceClientConfiguration.builder()
                .overrideConfiguration(overrides.build())
                .build();
          }

          @Override
          public String serviceName() {
            return "sqs";
          }

          @Override
          public void close() {}
        };
    List<BoundedPublisher> publishers =
        List.of(
            new SqsEventPublisher(client),
            new SqsAsyncEventPublisher(ImmediateAsyncFakes.adapt(SqsAsyncClient.class, client)));
    PublishBound expected =
        mode == Configuration.KNOWN ? PublishBound.of(timeout) : PublishBound.unbounded();
    for (var publisher : publishers) assertEquals(expected, publisher.publishBound());
  }
}
