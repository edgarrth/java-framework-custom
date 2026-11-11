package pe.axiz.spring.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import pe.axiz.core.messaging.EventPublisher;

class PublisherRouteValidationTest {
  @Test
  void nativeRouteFailureNamesTheResolvedMappingAndMissingVariable() {
    var beans = new DefaultListableBeanFactory();
    var properties =
        new AxizMessagingProperties(
            null, "", Map.of("audit", "${AXIZ_AUDIT_DESTINATION}"), null, null, null, null);
    assertThatThrownBy(
            () ->
                new AxizNativeMessagingAutoConfiguration()
                    .axizAsyncEventPublisher(
                        beans.getBeanProvider(AsyncEventPublisherAdapter.class),
                        beans.getBeanProvider(ObservationRegistry.class),
                        properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination 'audit' resolves to '${AXIZ_AUDIT_DESTINATION}'")
        .hasMessageContaining("unresolved variables: [AXIZ_AUDIT_DESTINATION]")
        .hasMessageContaining("configure axiz.messaging.destinations.audit");
  }

  @Test
  void propertyHintDoesNotReintroduceCredentialsFromAnInvalidLogicalUri() {
    var beans = new DefaultListableBeanFactory();
    var properties =
        new AxizMessagingProperties(
            null,
            "",
            Map.of(
                "https://user:secret@host/audit?token=hidden",
                "https://user:secret@host/queue?token=hidden"),
            null,
            null,
            null,
            null);
    assertThatThrownBy(
            () ->
                new AxizNativeMessagingAutoConfiguration()
                    .axizAsyncEventPublisher(
                        beans.getBeanProvider(AsyncEventPublisherAdapter.class),
                        beans.getBeanProvider(ObservationRegistry.class),
                        properties))
        .hasMessageContaining("configure axiz.messaging.destinations (see destination above)")
        .hasMessageNotContaining("secret")
        .hasMessageNotContaining("user:")
        .hasMessageNotContaining("token=");
  }

  @Test
  void instrumentationResolvesTheLogicalDestinationExactlyOnceAndPreservesRejection() {
    var target = mock(EventPublisher.class);
    var properties =
        new AxizMessagingProperties(
            null,
            "",
            Map.of("audit", "kafka:audit", "kafka:audit", "wrong"),
            null,
            null,
            null,
            null);
    var observed = new ObservedEventPublisher(target, ObservationRegistry.NOOP, properties);
    when(target.supports("kafka:audit")).thenReturn(true);
    assertTrue(observed.supports("audit"));
    assertFalse(observed.supports("unrouted"));
    verify(target).supports("kafka:audit");
    verify(target).supports("unrouted");
    verifyNoMoreInteractions(target);
  }
}
